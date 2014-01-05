import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.util.containers.StripedLockIntObjectConcurrentHashMap
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import org.neo4j.graphdb.DynamicRelationshipType
import org.neo4j.graphdb.RelationshipType
import org.neo4j.kernel.DefaultFileSystemAbstraction
import org.neo4j.unsafe.batchinsert.BatchInserter
import org.neo4j.unsafe.batchinsert.BatchInserters

import static liveplugin.PluginUtil.*
import static org.neo4j.graphdb.DynamicLabel.label
import static org.neo4j.helpers.collection.MapUtil.map
import static org.neo4j.helpers.collection.MapUtil.stringMap

//
// Sample neo4j cypher queries:
// match (class:PsiClassImpl) return count(class)
// match (file:PsiJavaFileImpl) return count(file)
// match (project:Project)<-[:CHILD_OF*1..3]-(child) return project,child
// match (file:PsiJavaFileImpl)<-[:CHILD_OF*1]-(child) where file.string='PsiJavaFile:JUnitMatchers.java' return file,child
// match (file)<-[:CHILD_OF*1]-(child) where file.string =~ '*package-info.java' return file,child
// match (file) where file.string =~ 'PsiDirectory.*extensions' return file
//

// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar


doInBackground("Copying PSI to neo4j") { indicator ->
	runReadAction {
		using(inserter(pluginPath + "/neo-database")) { BatchInserter inserter ->
			def key = neo4jKey()
			PersistPsiHierarchy.persist(project, inserter, key)
			PersistApiReferences.persist(project, inserter, key)
		}
	}
	show("Finished copying PSI")
}

class Callback {
	def onProject(Project project) {}
	def onSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {}
	def onVirtualFile(VirtualFile virtualFile, UserDataHolder parent) {}
	def onPsiElement(PsiElement psiElement, UserDataHolder parent) {}
}

class ProjectTraversal {
	private Project project
	private Callback callback

	def traverse(Project project, Callback callback) {
		this.project = project
		this.callback = callback

		callback.onProject(project)

		def roots = ProjectRootManager.getInstance(project).contentSourceRoots
		roots.each { persistSourceRoot(it, project) }
	}

	private persistSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {
		callback.onSourceRoot(sourceRoot, parent)
		sourceRoot.children.each{ persistFile(it, sourceRoot) }
	}

	private persistFile(VirtualFile virtualFile, UserDataHolder parent) {
		def psiItem = psiItem(virtualFile, project)
		if (psiItem == null) {
			callback.onVirtualFile(virtualFile, parent)
			virtualFile.children.each{ persistFile(it, virtualFile) }
		} else {
			persistPsiElement(psiItem, parent)
		}
	}

	private persistPsiElement(PsiElement element, UserDataHolder parent) {
		callback.onPsiElement(element, parent)

		// TODO try reducing db size by filtering whitespaces, brackets, etc
		element.children.each{ persistPsiElement(it, element) }
	}

	@Nullable private static PsiFileSystemItem psiItem(@Nullable VirtualFile file, @NotNull Project project) {
		if (file == null) return null

		def psiFile = PsiManager.getInstance(project).findFile(file)
		if (psiFile != null) return psiFile

		PsiManager.getInstance(project).findDirectory(file)
	}
}


class PersistApiReferences {
	static persist(Project project, BatchInserter inserter, Key<Long> neo4jKey) {
		RelationshipType refersTo = DynamicRelationshipType.withName("REFERS_TO")

		new ProjectTraversal().traverse(project, new Callback() {
			@Override def onPsiElement(PsiElement psiElement, UserDataHolder parent) {
				if (!(psiElement instanceof PsiReference)) return
				def resolvedElement = psiElement.asType(PsiReference).resolve()
				if (resolvedElement == null) return

				def elementId = neo4jKey.get(psiElement)
				def resolvedElementId = neo4jKey.get(resolvedElement)

				if (elementId != null && resolvedElementId != null) {

					inserter.createRelationship(elementId, resolvedElementId, refersTo, map())

				} else {
					// TODO No neo4j key for element: PsiJavaCodeReferenceElement:org.junit.runner.notification in /Users/dima/IdeaProjects/junit/src/main/java/org/junit/runner/notification/package-info.java
					// TODO if (resolvedElementId == null)
					if (elementId == null) {
						def rootParent = psiElement.parent
						while (rootParent != null && !(rootParent instanceof PsiFile)) rootParent = rootParent.parent
						log("No neo4j key for element: ${psiElement} in ${rootParent.asType(PsiFile).virtualFile.path}")
					}
				}
			}
		})
	}
}


class PersistPsiHierarchy {
	static persist(Project project, BatchInserter inserter, Key<Long> neo4jKey) {
		RelationshipType childOf = DynamicRelationshipType.withName("CHILD_OF")

		new ProjectTraversal().traverse(project, new Callback() {
			@Override def onProject(Project aProject) {
				long nodeId = inserter.createNode(map("string", project.name), label("Project"))
				neo4jKey.set(aProject, nodeId)
			}

			@Override def onSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {
				long nodeId = inserter.createNode(map("string", sourceRoot.path), label("SourceRoot"), label(sourceRoot.name))
				inserter.createRelationship(nodeId, neo4jKey.get(parent), childOf, map())
				neo4jKey.set(sourceRoot, nodeId)
			}

			@Override def onVirtualFile(VirtualFile virtualFile, UserDataHolder parent) {
				long nodeId = inserter.createNode(map("string", virtualFile.name), label(virtualFile.name))
				inserter.createRelationship(nodeId, neo4jKey.get(parent), childOf, map())
				neo4jKey.set(virtualFile, nodeId)
			}

			@Override def onPsiElement(PsiElement psiElement, UserDataHolder parent) {
				long nodeId = inserter.createNode(map("string", psiElement.toString()), label(psiElement.class.simpleName))
				inserter.createRelationship(nodeId, neo4jKey.get(parent), childOf, map())
				neo4jKey.set(psiElement, nodeId)
			}
		})
	}
}



static Key<Long> neo4jKey() {
	Key<Long> key = findKeyByName("Neo4jId")
	if (key == null) key = Key.create("Neo4jId")
	key
}

static <T> Key<T> findKeyByName(String name) {
	for (StripedLockIntObjectConcurrentHashMap.IntEntry<Key> entry : Key.allKeys.entries()) {
		if (name == entry.getValue().toString())  // assume that toString() returns name
			return entry.getValue()
	}
	null
}

static using(BatchInserter inserter, Closure closure) {
	try {
		closure(inserter)
	} finally {
		inserter.shutdown()
	}
}

static BatchInserter inserter(String pathToDatabase) {
	Map<String, String> config = stringMap("neostore.nodestore.db.mapped_memory", "90M")
	DefaultFileSystemAbstraction fileSystem = new DefaultFileSystemAbstraction()
	BatchInserters.inserter(pathToDatabase, fileSystem, config)
}
