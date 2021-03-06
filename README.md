### What is this?

This is experimental work-in-progress [IntelliJ](https://github.com/JetBrains/intellij-community) plugin for importing
[PSI](http://confluence.jetbrains.com/display/IDEADEV/IntelliJ+IDEA+Architectural+Overview#IntelliJIDEAArchitecturalOverview-PsiElements)
tree from IntelliJ into [Neo4j 2.0 database](http://www.neo4j.org/).
(Basically, PSI tree is [AST](http://en.wikipedia.org/wiki/Abstract_syntax_tree) with bindings.)

It was only tried on Java projects but should work with any language IntelliJ understands.

(Note that this plugin can only be run using [liveplugin](https://github.com/dkandalov/live-plugin)
in IntelliJ running under Java 7.)

You can try already imported PSI tree for [JUnit project](https://github.com/junit-team/junit)
by downloading it from [google drive](https://googledrive.com/host/0B5PfR1lF8o5STFZyVi1zSVVhemM/)
and pointing your local Neo4j to it (tested it with Neo4j 2.0.0-M06).


### Why?

It was interesting to try if Neo4j is easy to use for querying syntax graphs. E.g.
 - it might be that [cypher query language](http://docs.neo4j.org/chunked/stable/cypher-introduction.html)
   is easier than java for complex queries
 - Neo4j should scale horizontally so it might be possible to analyze really big code bases


### Database structure
(For ultimate reference please see [source code](https://github.com/dkandalov/neo-psi/blob/master/Neo4jPersistence.groovy).)

There is always root node with "Project" label.
It has child "SourceRoot" nodes which represent folders like "src/main/java", "src/test/java".
Under SourceRoots there is hierarchy of folders, files and PSI elements.

Child elements are connected to parents with directional "CHILD_OF" relationship.
Each "CHILD_OF" relationship has "index" property which can be used to determine child order
(index is guaranteed to grow but might not be sequential).

Elements might refer to other elements with directional "REFERS_TO" relationship
(e.g. type in variable declaration).

Each element has "string" property which contains its string representation (usually something like "PsiMethod:isException").


### Example queries
```
// Amount of java classes/interfaces:
match (class:PsiClassImpl) return count(class)

// Amount of java files:
match (file:PsiJavaFileImpl) return count(file)

// Project node and 3 levels of children
match (project:Project)<-[:CHILD_OF*1..3]-(child) return project,child

// "JUnitMatchers" class node and it's immediate children
match (class:PsiClassImpl)<-[:CHILD_OF*1]-(child)
where class.string='PsiClass:JUnitMatchers'
return class,child

// All references to anything in Assume.java (totally inefficient query):
match (file:PsiJavaFileImpl)<-[:CHILD_OF*]-(element)<-[:REFERS_TO]-(refElement)-[:CHILD_OF*]->(refFile:PsiJavaFileImpl)
where file.string='PsiJavaFile:Assume.java'
return distinct refFile
```

### Running this plugin
 - install [liveplugin](https://github.com/dkandalov/live-plugin)
 - add this project in toolwindow as plugin from git
 - in plugin folder run "gradle dependencies" to fetch neo4j dependencies in "lib" folder
 - after "// add-to-classpath" in plugin.groovy replace path with path to "lib" folder (this is limitation of LivePlugin at the moment)


### Similar tools
See [WiggleIndexer](https://github.com/raoulDoc/WiggleIndexer)
and ["Source-Code Queries with Graph Databases" paper](http://www.cl.cam.ac.uk/~am21/pk-urma-mycroft-scp.pdf)
by [@raoulUK](https://twitter.com/raoulUK).



