import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.containers.StripedLockIntObjectConcurrentHashMap

class Neo4jKey {
	private final Key<Long> neo4jKey = neo4jKey()

	Long getId(UserDataHolder dataHolder) {
		dataHolder.getUserData(neo4jKey)
	}

	def setId(UserDataHolder dataHolder, long id) {
		dataHolder.putUserData(neo4jKey, id)
	}

	private static Key<Long> neo4jKey() {
		Key<Long> key = findKeyByName("Neo4jId")
		if (key == null) key = Key.create("Neo4jId")
		key
	}

	@SuppressWarnings("GroovyAccessibility")
	private static <T> Key<T> findKeyByName(String name) {
		for (StripedLockIntObjectConcurrentHashMap.IntEntry<Key> entry : Key.allKeys.entries()) {
			if (name == entry.getValue().toString())  // assume that toString() returns name
				return entry.getValue()
		}
		null
	}
}
