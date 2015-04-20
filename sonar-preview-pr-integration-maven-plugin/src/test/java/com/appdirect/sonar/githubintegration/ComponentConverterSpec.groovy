package com.appdirect.sonar.githubintegration

import org.apache.maven.model.Build
import org.apache.maven.plugin.logging.SystemStreamLog
import org.apache.maven.project.MavenProject
import org.eclipse.egit.github.core.CommitFile

import spock.lang.Specification
import spock.lang.Unroll

class ComponentConverterSpec extends Specification {
	@Unroll
	def "Longest substring #left / #right"(String left, String right, int length) {
		expect:
		ComponentConverter.longestSubstr(left, right) == length

		where:
		left << [ 'abcdef', 'abcdefabcdef', 'aaaa', null, 'toto', null, '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java' ]
		right << [ 'def', 'totoabcdtoto', 'tutu', 'toto', null, null, '/home/toto/Workspace/sonar-pull-request-integration/src/test/resources/querydsl/querydsl-hazelcast' ]
		length << [ 3, 4, 0, 0, 0, 0, 19 ]
	}

	def "Check component to path conversion (flat project)"() {
		given:
		List<CommitFile> commits = [
				new CommitFile([ filename: '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java' ]),
				new CommitFile([ filename: '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java'])
		]

		List<MavenProject> projects = [
				new MavenProject([
						groupId: 'com.mysema.querydsl',
						artifactId: 'querydsl-root',
						file: new File('src/test/resources/querydsl/querydsl-root/pom.xml').getCanonicalFile()
				]),
				new MavenProject([
						groupId: 'com.mysema.querydsl',
						artifactId: 'querydsl-hazelcast',
						file: new File('src/test/resources/querydsl/querydsl-hazelcast/pom.xml').getCanonicalFile(),
						build: new Build([ sourceDirectory: new File("src/test/resources/querydsl/querydsl-hazelcast/src/main/java").getCanonicalPath() ])
				])
		]

		when:
		ComponentConverter c = new ComponentConverter("hazelcast", projects, commits, new SystemStreamLog());

		then:
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java') == '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java'
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java') == '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java'
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/test/java/com/mysema/query/hazelcast/impl/HazelcastSerializerTest.java') == null
	}

	def "Check component to path conversion (tree project)"() {
		given:
		List<CommitFile> commits = [
				new CommitFile([ filename: '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java' ]),
				new CommitFile([ filename: '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java'])
		]

		List<MavenProject> projects = [
				new MavenProject([
						groupId: 'com.mysema.querydsl',
						artifactId: 'querydsl-root',
						file: new File('src/test/resources/tree-project/pom.xml').getCanonicalFile()
				]),
				new MavenProject([
						groupId: 'com.mysema.querydsl',
						artifactId: 'querydsl-hazelcast',
						file: new File('src/test/resources/tree-project/querydsl-hazelcast/pom.xml').getCanonicalFile(),
						build: new Build([ sourceDirectory: new File("src/test/resources/tree-project/querydsl-hazelcast/src/main/java").getCanonicalPath() ])
				])
		]

		when:
		ComponentConverter c = new ComponentConverter("hazelcast", projects, commits, new SystemStreamLog());

		then:
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java') == '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/AbstractIMapQuery.java'
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java') == '/querydsl-hazelcast/src/main/java/com/mysema/query/hazelcast/impl/HazelcastSerializer.java'
		c.componentToPath('com.mysema.querydsl:querydsl-hazelcast:hazelcast:src/test/java/com/mysema/query/hazelcast/impl/HazelcastSerializerTest.java') == null
	}
}
