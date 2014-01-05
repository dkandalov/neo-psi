import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.neo4j.graphdb.DynamicLabel
import org.neo4j.graphdb.DynamicRelationshipType
import org.neo4j.graphdb.RelationshipType
import org.neo4j.kernel.DefaultFileSystemAbstraction
import org.neo4j.unsafe.batchinsert.BatchInserter
import org.neo4j.unsafe.batchinsert.BatchInserters

import static liveplugin.PluginUtil.*
import static org.neo4j.helpers.collection.MapUtil.map
import static org.neo4j.helpers.collection.MapUtil.stringMap

//
// Sample neo4j cypher queries:
// match (class:PsiClassImpl) return count(class)
// match (file:PsiJavaFile) return count(file)
// match (project:Project)<-[:CHILD_OF*1..3]-(child) return project,child
// match (file:PsiJavaFileImpl)<-[:CHILD_OF*1]-(child) where file.string='PsiJavaFile:SimpleTest.java' return file,child
//

// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar

doInBackground("Copying PSI to neo4j") { indicator ->
	runReadAction {
		using(inserter(pluginPath + "/neo-database")) { BatchInserter inserter ->
			new PersistPsi(inserter).persist(project)
		}
	}
	show("Finished copying PSI")
}

class PersistPsi {
	private final RelationshipType childOf = DynamicRelationshipType.withName("CHILD_OF")

	private final BatchInserter inserter
	private Project project

	PersistPsi(BatchInserter inserter) {
		this.inserter = inserter
	}

	def persist(Project project) {
		this.project = project

		long projectNode = inserter.createNode(map("string", project.name), DynamicLabel.label("Project"))

		def roots = ProjectRootManager.getInstance(project).contentSourceRoots
		roots.each { VirtualFile sourceRoot ->
			persistSourceRoot(sourceRoot, projectNode)
		}
	}

	private persistSourceRoot(VirtualFile sourceRoot, long projectNode) {
		long sourceRootNode = inserter.createNode(map("string", sourceRoot.path), DynamicLabel.label("SourceRoot"))
		inserter.createRelationship(sourceRootNode, projectNode, childOf, map())

		sourceRoot.each { child ->
			persistFile(child, sourceRootNode)
		}
	}

	private persistFile(VirtualFile virtualFile, long parentNode) {
		def psiFile = psiFile(virtualFile, project)
		if (psiFile == null) {
			long node = inserter.createNode(map("string", virtualFile.name), DynamicLabel.label(virtualFile.name))
			inserter.createRelationship(node, parentNode, childOf, map())

			virtualFile.children.each{
				persistFile(it, node)
			}

		} else {
			persistPsiElement(psiFile, parentNode)
		}
	}

	private persistPsiElement(PsiElement element, long parentNodeId) {
		long node = inserter.createNode(map("string", element.toString()), DynamicLabel.label(element.class.simpleName))
		inserter.createRelationship(node, parentNodeId, childOf, map())

		element.children.findAll{!(it instanceof PsiWhiteSpace)}.each {
			persistPsiElement(it, node)
		}
	}
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
