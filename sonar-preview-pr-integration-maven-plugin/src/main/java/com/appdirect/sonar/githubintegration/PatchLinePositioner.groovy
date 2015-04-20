package com.appdirect.sonar.githubintegration

class PatchLinePositioner implements LinePositioner {
	Map<Integer, Integer> lineToPosition = [:]
	int firstLine = -1

	public PatchLinePositioner(String patch) {
		int position = 0
		int currentLine = -1

		patch.eachLine { line ->
			if (line.startsWith("@@")) {
				currentLine = line.replaceAll('@@.*\\+', '').replaceAll('\\,.*', '').replaceAll("\\D", "") as int
			} else if (line.startsWith( "+" )) {
				lineToPosition << [ (currentLine): position ]
				if (firstLine < 0) {
					firstLine = position
				}
			}

			if (!line.startsWith('-') && !line.startsWith('@@')) {
				currentLine++
			}
			position++
		}
	}

	@Override
	public int toPosition(Integer line) {
		if (line) {
			lineToPosition.get(line, -1)
		} else {
			firstLine
		}
	}
}
