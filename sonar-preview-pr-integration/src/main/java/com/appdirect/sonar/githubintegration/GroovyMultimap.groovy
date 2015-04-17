package com.appdirect.sonar.githubintegration

trait GroovyMultimap {
	abstract put(key, value)

	GroovyMultimap leftShift(Map m) {
		m.each {
			this.put(it.key, it.value)
		}

		return this
	}
}
