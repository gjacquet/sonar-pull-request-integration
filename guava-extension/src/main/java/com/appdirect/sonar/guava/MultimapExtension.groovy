package com.appdirect.sonar.guava
import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import com.google.common.collect.Multimaps

public class MultimapExtension {
	private static <T extends Multimap> T multimapLeftShift(T self, Map m) {
		self.putAll(Multimaps.forMap(m))

		return self
	}

	public static LinkedHashMultimap leftShift(LinkedHashMultimap self, Map m) {
		multimapLeftShift(self, m)
	}
}
