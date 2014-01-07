import ProjectTraversal
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex
import org.neo4j.unsafe.batchinsert.BatchInserter

import static Persistence.*
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
			def traversalIndicator = new ProjectTraversalIndicator(indicator, 2 * amountOfFilesIn(project))

			catchingAll{
				using(inserter(pluginPath + "/neo-database")){ BatchInserter inserter ->
					def key = new Neo4jKey()
					def traversal = new ProjectTraversal(traversalIndicator, psiFilter())

					traversal.traverse(project, persistPsiHierarchy(inserter, key))
					traversalIndicator.expectAsManyFilesAsTraversed()
					traversal.traverse(project, persistPsiReferences(inserter, key))
				}
			}
		}
	}).get()
	show("Finished copying PSI")
}

class ProjectTraversalIndicator {
	private final indicator
	private int expectedFiles
	private int amountOfFiles

	ProjectTraversalIndicator(indicator, int expectedFiles) {
		this.indicator = indicator
		this.expectedFiles = expectedFiles

		indicator.fraction = 0.0
	}

	def expectAsManyFilesAsTraversed() {
		expectedFiles = amountOfFiles * 2
	}

	boolean isCanceled() {
		indicator.canceled
	}

	def onFileTraversed() {
		amountOfFiles++
		def fraction = amountOfFiles / expectedFiles
		indicator.fraction = (fraction > 1.0 ? 1.0 : fraction)
	}
}

static int amountOfFilesIn(Project project) {
	def scope = GlobalSearchScope.projectScope(project)
	int result = 0
	def fileTypes = FileTypeManager.instance.registeredFileTypes.findAll{it instanceof LanguageFileType}
	for (FileType fileType : fileTypes) {
		result += FileBasedIndex.instance.getContainingFiles(FileTypeIndex.NAME, fileType, scope).size()
	}
	result
}