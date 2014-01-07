import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import liveplugin.IntegrationTestsRunner
import org.junit.Test
import org.neo4j.cypher.ExecutionEngine
import org.neo4j.graphdb.factory.GraphDatabaseFactory
import org.neo4j.unsafe.batchinsert.BatchInserter

import static Neo4jPersistence.*
import static org.neo4j.kernel.impl.util.StringLogger.DEV_NULL
// add-to-classpath $HOME/IdeaProjects/neo4j-tutorial/lib/*.jar

IntegrationTestsRunner.runIntegrationTests([Neo4jPersistenceTest], project, pluginPath)

class Neo4jPersistenceTest {

	@Test void "import PSI into Neo4j"() {
		def javaFile = asJavaPsi("Sample.java", """
			class A {
				A(B b) {}

				private static class B {
					String s = "a string";
				}
			}
		""")

		try {
			def databasePath = pluginPath + "/test-neo-database"
			using(inserter(databasePath)){ BatchInserter inserter ->
				def key = new Neo4jKey()
				traverse(javaFile, persistPsiHierarchy(inserter, key))
				traverse(javaFile, persistPsiReferences(inserter, key))
			}

			def database = new GraphDatabaseFactory().newEmbeddedDatabase(databasePath)
			ExecutionEngine engine = new ExecutionEngine(database, DEV_NULL)

			def amountOfNodes = engine.execute("match (n) return count(n)").javaColumnAs("count(n)").next()
			def childRelations = engine.execute("match ()<-[r:CHILD_OF]-() return count(r)").javaColumnAs("count(r)").next()
			def referenceRelations = engine.execute("match ()<-[r:REFERS_TO]-() return count(r)").javaColumnAs("count(r)").next()

			assert amountOfNodes == 70
			assert childRelations == 69
			assert referenceRelations == 2

		} finally {
			FileUtilRt.delete(new File(pluginPath + "/test-neo-database"))
		}
	}

	private static traverse(PsiElement element, TraversalListener callback, UserDataHolder parent = null, int index = -1) {
		parent = (parent != null ? parent : element)
		callback.onPsiElement(element, parent, index)
		element.children.eachWithIndex{ child, i -> traverse(child, callback, element, i) }
	}

	private PsiJavaFile asJavaPsi(String fileName, String javaCode) {
		def fileFactory = PsiFileFactory.getInstance(project)
		fileFactory.createFileFromText(fileName, JavaFileType.INSTANCE, javaCode) as PsiJavaFile
	}

	Neo4jPersistenceTest(Map context) {
		this.project = context.project
		this.pluginPath = context.pluginPath
	}

	private final Project project
	private final String pluginPath
}