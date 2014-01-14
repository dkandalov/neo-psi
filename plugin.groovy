import ProjectTraversal
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.io.FileUtil
import org.neo4j.unsafe.batchinsert.BatchInserter

import static Neo4jPersistence.*
import static ProjectTraversalIndicator.amountOfFilesIn
import static java.util.concurrent.Executors.newSingleThreadExecutor
import static liveplugin.PluginUtil.*

// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar

// Please note that this plugin will only work in IntelliJ running under java 1.7
// (this is because neo4j 2.0 libs are compiled for java 7)

// Expected results for JUnit:
// - 283199: match (n) return count(n)
// - 282547: match ()<-[r:CHILD_OF]-() return count(r)
// - 39231: match ()<-[r:REFERS_TO]-() return count(r)

if (isIdeStartup) return


doInModalMode("Importing PSI into Neo4j") { ProgressIndicator indicator ->
	newSingleThreadExecutor().submit({
		runReadAction{
			try {

				def databasePath = pluginPath + "/neo-database"

				using(inserter(databasePath)){ BatchInserter inserter ->
					def traversalIndicator = new ProjectTraversalIndicator(indicator, 2 * amountOfFilesIn(project))
					def key = new Neo4jKey()
					def traversal = new ProjectTraversal(traversalIndicator, psiFilter())

					traversal.traverse(project, persistPsiHierarchy(inserter, key))
					traversalIndicator.expectAsManyFilesAsTraversed()
					traversal.traverse(project, persistPsiReferences(inserter, key))
				}

				if (indicator.canceled) {
					FileUtil.delete(new File(databasePath))
					show("Canceled importing PSI to ${databasePath}")
				} else {
					show("Finished importing PSI to ${databasePath}")
				}

			} catch (Exception e) {
				showInConsole(e, project)
			}
		}
	}).get()
}
