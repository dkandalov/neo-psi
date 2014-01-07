import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import liveplugin.IntegrationTestsRunner
import liveplugin.PluginUtil
import org.junit.Test
import org.neo4j.unsafe.batchinsert.BatchInserter

import static Persistence.*
// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar

IntegrationTestsRunner.runIntegrationTests([ATest], project, pluginPath)

class ATest {

	@Test void "import PSI into Neo4j"() {
		def javaFile = asJavaPsi("Sample.java", """
			class Sample {
				Sample() {}
				void doWhileLoop() { do {} while (true) }
				void nestedDoWhileLoops() {
					do {
						do {} while (true)
					} while (true)
				}
			}
		""")

		try {
			using(inserter(pluginPath + "/test-neo-database")){ BatchInserter inserter ->
				def key = new Neo4jKey()
				traverse(javaFile, persistPsiHierarchy(inserter, key))
				traverse(javaFile, persistPsiReferences(inserter, key))
			}
		} finally {
			FileUtilRt.delete(new File(pluginPath + "/test-neo-database"))
		}
	}

	private static traverse(PsiElement element, Callback callback, UserDataHolder parent = null, int index = -1) {
		parent = (parent != null ? parent : element)
		callback.onPsiElement(element, parent, index)
		element.children.eachWithIndex{ child, i -> traverse(child, callback, element, i) }
	}

	private PsiJavaFile asJavaPsi(String fileName, String javaCode) {
		def fileFactory = PsiFileFactory.getInstance(project)
		fileFactory.createFileFromText(fileName, JavaFileType.INSTANCE, javaCode) as PsiJavaFile
	}

	ATest(Map context) {
		this.project = context.project
		this.pluginPath = context.pluginPath
		PluginUtil.show(pluginPath)
	}

	private final Project project
	private final String pluginPath
}