package com.appdirect.sonar.githubintegration

import spock.lang.Specification
import spock.lang.Unroll

class PatchLinePositionerSpec extends Specification {
	def "Check to position for new file"() {
		when:
		PatchLinePositioner lp = new PatchLinePositioner(new File('src/test/resources/newFile.patch').text);

		then:
		lp.lineToPosition.size() == 169
		lp.toPosition(5) == 5
		lp.toPosition(146) == 146
		lp.toPosition(170) == -1
		lp.toPosition(171) == -1
	}

	@Unroll
	def "Check to position for diff file line #line"(int line, position) {
		when:
		PatchLinePositioner lp = new PatchLinePositioner(new File('src/test/resources/inclusionsExclusion.patch').text);

		then:
		lp.lineToPosition.size() == 6
		lp.toPosition(line) == position

		where:
		line << [ 5, 328, 329, 330, 331, 332, 333, 338, 344, 345, 346 ]
		position << [ -1, -1, 10, 11, 12, 13, -1, -1, -1, 22, -1 ]
	}
}
