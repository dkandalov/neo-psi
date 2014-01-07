import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.indexing.FileBasedIndex

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

	static int amountOfFilesIn(Project project) {
		def scope = GlobalSearchScope.projectScope(project)
		int result = 0
		def fileTypes = FileTypeManager.instance.registeredFileTypes.findAll{it instanceof LanguageFileType}
		for (FileType fileType : fileTypes) {
			result += FileBasedIndex.instance.getContainingFiles(FileTypeIndex.NAME, fileType, scope).size()
		}
		result
	}
}
