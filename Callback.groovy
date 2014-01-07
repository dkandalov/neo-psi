import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement

class Callback {
	def onProject(Project project) {}
	def onSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {}
	def onVirtualFile(VirtualFile virtualFile, UserDataHolder parent, int index) {}
	def onPsiElement(PsiElement psiElement, UserDataHolder parent, int index) {}
}