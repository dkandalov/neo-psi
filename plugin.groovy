import org.neo4j.unsafe.batchinsert.BatchInserter
import ProjectTraversal

import static java.util.concurrent.Executors.newSingleThreadExecutor
import static liveplugin.PluginUtil.*
import static Persistence.*
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

doInModalMode("Importing PSI into Neo4j") { indicator ->
	newSingleThreadExecutor().submit({
		runReadAction{
			catchingAll{
				using(inserter(pluginPath + "/neo-database")){ BatchInserter inserter ->
					// TODO estimate progress
					def key = new Neo4jKey()
					def traversal = new ProjectTraversal(indicator, psiFilter())
					traversal.traverse(project, persistPsiHierarchy(inserter, key))
					traversal.traverse(project, persistPsiReferences(inserter, key))
				}
			}
		}
	}).get()
	show("Finished copying PSI")
}

