import ProjectTraversal
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import org.neo4j.unsafe.batchinsert.BatchInserter

import static Neo4jPersistence.*
import static ProjectTraversalIndicator.amountOfFilesIn
import static java.util.concurrent.Executors.newSingleThreadExecutor
import static liveplugin.PluginUtil.*
//
// Sample neo4j cypher queries:
// match (class:PsiClassImpl) return count(class)
// match (file:PsiJavaFileImpl) return count(file)
// match (project:Project)<-[:CHILD_OF*1..3]-(child) return project,child
// match (file:PsiJavaFileImpl)<-[:CHILD_OF*1]-(child) where file.string='PsiJavaFile:JUnitMatchers.java' return file,child
// match (file) where file.string =~ 'PsiDirectory.*extensions' return file
//

// Expected results for JUnit:
// - 283199: match (n) return count(n)
// - 282547: match ()<-[r:CHILD_OF]-() return count(r)
// - 39231: match ()<-[r:REFERS_TO]-() return count(r)

// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar

doInModalMode("Importing PSI into Neo4j") { ProgressIndicator indicator ->
	newSingleThreadExecutor().submit({
		runReadAction{
			try {

				def traversalIndicator = new ProjectTraversalIndicator(indicator, 2 * amountOfFilesIn(project))
				def databasePath = pluginPath + "/neo-database"

				using(inserter(databasePath)){ BatchInserter inserter ->
					def key = new Neo4jKey()
					def traversal = new ProjectTraversal(traversalIndicator, psiFilter())

					traversal.traverse(project, persistPsiHierarchy(inserter, key))
					traversalIndicator.expectAsManyFilesAsTraversed()
					traversal.traverse(project, persistPsiReferences(inserter, key))
				}

				if (indicator.canceled) {
					FileUtil.delete(new File(databasePath))
					show("Canceled copying PSI")
				} else {
					show("Finished copying PSI")
				}

			} catch (Exception e) {
				showInConsole(e, project)
			}
		}
	}).get()
}
