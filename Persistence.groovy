import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.neo4j.graphdb.DynamicRelationshipType
import org.neo4j.graphdb.NotFoundException
import org.neo4j.graphdb.RelationshipType
import org.neo4j.kernel.DefaultFileSystemAbstraction
import org.neo4j.unsafe.batchinsert.BatchInserter
import org.neo4j.unsafe.batchinsert.BatchInserters

import static com.intellij.psi.JavaTokenType.*
import static liveplugin.PluginUtil.log
import static org.neo4j.graphdb.DynamicLabel.label
import static org.neo4j.helpers.collection.MapUtil.map
import static org.neo4j.helpers.collection.MapUtil.stringMap

class Persistence {

	static Callback persistPsiReferences(BatchInserter inserter, Neo4jKey neo4jKey) {
		RelationshipType refersTo = DynamicRelationshipType.withName("REFERS_TO")

		new Callback() {
			@Override def onPsiElement(PsiElement psiElement, UserDataHolder parent, int index) {
				if (!(psiElement instanceof PsiReference)) return
				def resolvedElement = psiElement.asType(PsiReference).resolve()
				if (resolvedElement == null) return

				def elementId = neo4jKey.getId(psiElement)
				def resolvedElementId = neo4jKey.getId(resolvedElement)

				def elementIsOutsideProject = (resolvedElementId == null)
				if (elementIsOutsideProject) {
					resolvedElementId = inserter.createNode(map("string", resolvedElement.toString()), label(resolvedElement.class.simpleName))
					neo4jKey.setId(resolvedElement, resolvedElementId)
				}

				if (elementId != null && resolvedElementId != null) {

					try {
						inserter.createRelationship(elementId, resolvedElementId, refersTo, map())
					} catch (NotFoundException ignored) {
						log("Failed to create relationship ${elementId} -> ${resolvedElementId} (${psiElement} -> ${resolvedElement}")
					}

				} else {
					// TODO No neo4j key for element: PsiJavaCodeReferenceElement:org.junit.runner.notification in /Users/dima/IdeaProjects/junit/src/main/java/org/junit/runner/notification/package-info.java
					if (elementId == null) {
						def rootParent = psiElement.parent
						while (rootParent != null && !(rootParent instanceof PsiFile)) rootParent = rootParent.parent
						log("No neo4j key for element: ${psiElement} in ${rootParent.asType(PsiFile).virtualFile.path}")
					}
				}
			}
		}
	}

	static Callback persistPsiHierarchy(BatchInserter inserter, Neo4jKey neo4jKey) {
		RelationshipType childOf = DynamicRelationshipType.withName("CHILD_OF")

		new Callback() {
			@Override def onProject(Project aProject) {
				long nodeId = inserter.createNode(map("string", aProject.name), label("Project"))
				neo4jKey.setId(aProject, nodeId)
			}

			@Override def onSourceRoot(VirtualFile sourceRoot, UserDataHolder parent) {
				long nodeId = inserter.createNode(map("string", sourceRoot.path), label("SourceRoot"), label(sourceRoot.name))
				neo4jKey.setId(sourceRoot, nodeId)
				inserter.createRelationship(nodeId, neo4jKey.getId(parent), childOf, map())
			}

			@Override def onVirtualFile(VirtualFile virtualFile, UserDataHolder parent, int index) {
				long nodeId = inserter.createNode(map("string", virtualFile.name, "index", index), label(virtualFile.name))
				neo4jKey.setId(virtualFile, nodeId)
				inserter.createRelationship(nodeId, neo4jKey.getId(parent), childOf, map())
			}

			@Override def onPsiElement(PsiElement psiElement, UserDataHolder parent, int index) {
				if (psiElement instanceof PsiReference) psiElement.resolve()

				long nodeId = inserter.createNode(map("string", psiElement.toString()), label(psiElement.class.simpleName))
				neo4jKey.setId(psiElement, nodeId)
				inserter.createRelationship(nodeId, neo4jKey.getId(parent), childOf, map("index", index))
			}
		}
	}


	static Closure psiFilter() {
		return { PsiElement element ->
			if (element instanceof PsiWhiteSpace) false
			else if (element instanceof PsiJavaToken &&
					(element.tokenType == LBRACE || element.tokenType == RBRACE ||
					element.tokenType == LPARENTH || element.tokenType == RPARENTH ||
					element.tokenType == SEMICOLON || element.tokenType == COMMA)) false
			else true
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

}
