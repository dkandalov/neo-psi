import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable

class ProjectTraversal {
	private final Closure acceptPsi
	private final def indicator
	private Project project
	private Callback callback

	ProjectTraversal(indicator, Closure acceptPsi = null) {
		this.indicator = indicator
		this.acceptPsi = acceptPsi
	}

	def traverse(Project project, Callback callback) {
		this.project = project
		this.callback = callback

		callback.onProject(project)

		def roots = ProjectRootManager.getInstance(project).contentSourceRoots
		roots.each { persistSourceRoot(it, project) }
	}

	private persistSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {
		if (indicator.canceled) return

		callback.onSourceRoot(sourceRoot, parent)
		sourceRoot.children.eachWithIndex{ child, childIndex -> persistFile(child, sourceRoot, childIndex) }
	}

	private persistFile(VirtualFile virtualFile, UserDataHolder parent, int index) {
		indicator.onFileTraversed()
		if (indicator.canceled) return

		def psiItem = psiItem(virtualFile, project)
		if (psiItem == null) {
			callback.onVirtualFile(virtualFile, parent, index)
			virtualFile.eachWithIndex{ child, childIndex -> persistFile(child, virtualFile, childIndex) }
		} else {
			persistPsiElement(psiItem, parent, index)
		}
	}

	private persistPsiElement(PsiElement element, UserDataHolder parent, int index) {
		if (element instanceof PsiFile && !element.directory) indicator.onFileTraversed()
		if (indicator.canceled) return
		if (acceptPsi != null && !acceptPsi(element)) return

		callback.onPsiElement(element, parent, index)

		element.children.eachWithIndex{ child, childIndex -> persistPsiElement(child, element, childIndex) }
	}

	@Nullable private static PsiFileSystemItem psiItem(@Nullable VirtualFile file, @NotNull Project project) {
		if (file == null) return null

		def psiFile = PsiManager.getInstance(project).findFile(file)
		if (psiFile != null) return psiFile

		PsiManager.getInstance(project).findDirectory(file)
	}
}
