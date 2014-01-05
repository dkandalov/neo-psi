import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
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
			new PersistPsiHierarchy(inserter, neo4jKey()).persist(project)
			new PersistApiReferences(inserter, neo4jKey()).persist(project)
		}
	}
	show("Finished copying PSI")
}


class PersistApiReferences {
	private final RelationshipType refersTo = DynamicRelationshipType.withName("REFERS_TO")

	private final BatchInserter inserter
	private final Key<Long> neo4jKey
	private Project project

	PersistApiReferences(BatchInserter inserter, Key<Long> neo4jKey) {
		this.inserter = inserter
		this.neo4jKey = neo4jKey
	}

	def persist(Project project) {
		this.project = project

		def roots = ProjectRootManager.getInstance(project).contentSourceRoots
		roots.each { VirtualFile sourceRoot ->
			persistSourceRoot(sourceRoot)
		}
	}

	private persistSourceRoot(VirtualFile sourceRoot) {
		sourceRoot.each { child ->
			persistFile(child)
		}
	}

	private persistFile(VirtualFile virtualFile) {
		def psiFile = psiFile(virtualFile, project)
		if (psiFile == null) {
			virtualFile.children.each{
				persistFile(it)
			}
		} else {
			persistPsiElement(psiFile)
		}
	}

	private persistPsiElement(PsiElement element) {
		if (element instanceof PsiReference) {
			def resolvedElement = element.resolve()
			if (resolvedElement == null) return

			def elementId = element.getUserData(neo4jKey)
			def resolvedElementId = resolvedElement.getUserData(neo4jKey)

			if (elementId != null && resolvedElementId != null) {
				inserter.createRelationship(elementId, resolvedElementId, refersTo, map())
			} else {
				// TODO if (resolvedElementId == null)
				if (elementId == null) {
					def parent = element.parent
					while (parent != null && !(parent instanceof PsiFile)) parent = parent.parent
					log("No neo4j key for element: ${element} in ${parent.asType(PsiFile).virtualFile.path}")
				}
			}
		}

		element.children.findAll{ !(it instanceof PsiWhiteSpace) }.each{
			persistPsiElement(it)
		}
	}
}


class PersistPsiHierarchy {
	private final RelationshipType childOf = DynamicRelationshipType.withName("CHILD_OF")

	private final BatchInserter inserter
	private final Key<Long> neo4jKey
	private Project project

	PersistPsiHierarchy(BatchInserter inserter, Key<Long> neo4jKey) {
		this.inserter = inserter
		this.neo4jKey = neo4jKey
	}

	def persist(Project project) {
		this.project = project

		long projectNode = inserter.createNode(map("string", project.name), label("Project"))

		def roots = ProjectRootManager.getInstance(project).contentSourceRoots
		roots.each { VirtualFile sourceRoot ->
			persistSourceRoot(sourceRoot, projectNode)
		}
	}

	private persistSourceRoot(VirtualFile sourceRoot, long projectNode) {
		long sourceRootNode = inserter.createNode(map("string", sourceRoot.path), label("SourceRoot"), label(sourceRoot.name))
		inserter.createRelationship(sourceRootNode, projectNode, childOf, map())

		sourceRoot.each { child ->
			persistFile(child, sourceRootNode)
		}
	}

	private persistFile(VirtualFile virtualFile, long parentNode) {
		def psiItem = psiItem(virtualFile, project)
		if (psiItem == null) {
			long node = inserter.createNode(map("string", virtualFile.name), label(virtualFile.name))
			inserter.createRelationship(node, parentNode, childOf, map())

			virtualFile.children.each{ child ->
				persistFile(child, node)
			}
		} else {
			persistPsiElement(psiItem, parentNode)
		}
	}

	private persistPsiElement(PsiElement element, long parentNodeId) {
		long node = inserter.createNode(map("string", element.toString()), label(element.class.simpleName))
		inserter.createRelationship(node, parentNodeId, childOf, map())

		element.putUserData(neo4jKey, node)

		element.children.findAll{!(it instanceof PsiWhiteSpace)}.each { child ->
			persistPsiElement(child, node)
		}
	}

	@Nullable private static PsiFileSystemItem psiItem(@Nullable VirtualFile file, @NotNull Project project) {
		if (file == null) return null

		def psiFile = PsiManager.getInstance(project).findFile(file)
		if (psiFile != null) return psiFile

		PsiManager.getInstance(project).findDirectory(file)
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
