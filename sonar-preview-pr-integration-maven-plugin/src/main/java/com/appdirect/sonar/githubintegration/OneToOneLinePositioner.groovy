package com.appdirect.sonar.githubintegration

/**
 * Created by guillaume.jacquet on 4/16/15.
 */
class OneToOneLinePositioner implements LinePositioner {
	@Override
	int toPosition(Integer line) {
		line ?: 1
	}
}
