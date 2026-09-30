/*
 * Copyright (c) 2023, LlemonDuck <napkinorton@gmail.com>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.runeassist.flip.ui;

import com.runeassist.flip.model.ItemIdName;
import org.apache.commons.text.similarity.*;

import javax.inject.Singleton;
import java.util.function.ToDoubleFunction;

@Singleton
public class FuzzySearchScorer
{

	private final SimilarityScore<Double> baseAlgorithm = new JaroWinklerDistance();

	public Double score(String query, String itemName)
	{
		query = query.toLowerCase().replace('-', ' ');
		itemName = itemName.toLowerCase().replace('-', ' ');

		String[] queryWords = query.split(" ");
		String[] itemWords = itemName.split(" ");
		double lcsScore = 0.0;
		for (String queryWord : queryWords)
		{
			for (String itemWord : itemWords)
			{
				int lcsLen = new LongestCommonSubsequence().longestCommonSubsequence(queryWord, itemWord).length();
				lcsScore = Math.max(lcsScore, ((double) lcsLen) / queryWord.length());
			}
		}

		double proximityScore = Math.log10(10 * baseAlgorithm.apply(query, itemName)) - 0.5;

		return lcsScore + proximityScore - 1.0;
	}

	public ToDoubleFunction<ItemIdName> comparator(String query)
	{
		return item -> score(
			query.toLowerCase().replace('-', ' '),
			item.getName().toLowerCase().replace('-', ' ')
		);
	}

}
