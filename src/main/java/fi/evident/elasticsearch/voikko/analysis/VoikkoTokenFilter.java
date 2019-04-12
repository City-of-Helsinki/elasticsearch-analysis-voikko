/*
 * Copyright 2013 Evident Solutions Oy
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the Lesser GNU General Public License
 * along with this program. If not, see <​http://www.gnu.org/licenses/>.
 */

package fi.evident.elasticsearch.voikko.analysis;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.puimula.libvoikko.Analysis;
import org.puimula.libvoikko.Voikko;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;

final class VoikkoTokenFilter extends TokenFilter {

    private State current;
    private final VoikkoPool pool;
    private final Voikko voikko;
    private final VoikkoTokenFilterConfiguration cfg;

    private final CharTermAttribute charTermAttribute = addAttribute(CharTermAttribute.class);
    private final PositionIncrementAttribute positionIncrementAttribute = addAttribute(PositionIncrementAttribute.class);

    private final Deque<String> alternatives = new ArrayDeque<String>();
    private final AnalysisCache analysisCache;

    private static final Pattern VALID_WORD_PATTERN = Pattern.compile("[a-zA-ZåäöÅÄÖ-]+");

    VoikkoTokenFilter(TokenStream input, VoikkoPool pool, AnalysisCache analysisCache, VoikkoTokenFilterConfiguration cfg) throws InterruptedException {
        super(input);
        this.pool = pool;
        this.voikko = pool.takeVoikko();
        this.analysisCache = analysisCache;
        this.cfg = cfg;
    }

    @Override
    public void close() throws IOException {
        super.close();
        pool.release(voikko);
    }

    @Override
    public boolean incrementToken() throws IOException {
        if (!alternatives.isEmpty()) {
            outputAlternative(alternatives.removeFirst());
            return true;
        }

        if (input.incrementToken()) {
            analyzeToken();
            return true;
        }

        return false;
    }

    private void analyzeToken() {
        if (!isCandidateForAnalyzation(charTermAttribute))
            return;

        List<String> baseForms = analyze(charTermAttribute);
        if (baseForms.isEmpty())
            return;

        charTermAttribute.setEmpty().append(baseForms.get(0));

        if ((cfg.analyzeAll || cfg.expandCompounds) && baseForms.size() > 1) {
            current = captureState();

            for (String baseForm : baseForms.subList(1, baseForms.size()))
                alternatives.add(baseForm);
        }
    }

    private List<String> analyze(CharSequence wordSeq) {
        String word = wordSeq.toString();
        List<String> result = analysisCache.get(word);
        if (result == null) {
            result = analyzeUncached(word);
            analysisCache.put(word, result);
        }
        return result;
    }

    private List<String> analyzeUncached(String word) {
        List<Analysis> results = voikko.analyze(word);
        Set<String> baseForms = new LinkedHashSet<String>();

        for (Analysis result : results) {
            String baseForm = result.get("BASEFORM");
            if (baseForm != null) {
                baseForms.add(baseForm);
                if (!cfg.analyzeAll) {
                    break;
                }
            }
        }
        if (cfg.expandCompounds) {
            for (String compound : expandCompounds(results)) {
                baseForms.add(compound);
            }
        }
        List<String> ret = new ArrayList<String>(baseForms.size());
        ret.addAll(baseForms);
        return ret;
    }

    private void outputAlternative(String token) {
        restoreState(current);

        positionIncrementAttribute.setPositionIncrement(0);
        charTermAttribute.setEmpty().append(token);
    }

    private boolean isCandidateForAnalyzation(CharSequence word) {
        return word.length() >= cfg.minimumWordSize && word.length() <= cfg.maximumWordSize && VALID_WORD_PATTERN.matcher(word).matches();
    }

    private Set<String> expandCompounds(List<Analysis> analysisList) {
        Set<String> compoundForms = new LinkedHashSet<String>();

        for (Analysis analysis: analysisList) {
            if (!analysis.containsKey("WORDBASES")) {
                continue;
            }
            String wordbases = analysis.get("WORDBASES");
            // Split by plus sign (unless right after an open parenthesis)
            String matches[] = wordbases.split("(?<!\\()\\+");

            int currentPos = 0, lastPos = 0;
            String lastWordBody = "";
            assert matches.length > 1;
            // The string starts with a plus sign, so skip the first (empty) entry.
            for (int i = 1; i <= matches.length - 1; i++) {
                String wordAnalysis, wordBody, baseForm;

                // Get rid of equals sign in e.g. di=oksidi.
                wordAnalysis = matches[i].replaceAll("=", "");;
                int parenPos = wordAnalysis.indexOf('(');
                if (parenPos == -1) {
                    wordBody = baseForm = wordAnalysis;
                } else {
                    // Word body is before the parenthesis
                    wordBody = wordAnalysis.substring(0, parenPos);
                    // Base form or derivative is in parenthesis
                    baseForm = wordAnalysis.substring(parenPos + 1, wordAnalysis.length() - 1);
                }

                String word;
                int wordOffset, wordLen;
                boolean isDerivative = baseForm.startsWith("+");
                if (isDerivative) {
                    // Derivative suffix, merge with word body
                    word = lastWordBody + wordBody;
                    wordOffset = lastPos;
                    wordLen = word.length();
                } else {
                    word = baseForm;
                    wordOffset = currentPos;
                    wordLen = word.length();
                    lastWordBody = wordBody;
                    lastPos = currentPos;
                    currentPos += baseForm.length();
                }

                // Make sure we don't exceed the length of the original term
                int termLen = charTermAttribute.toString().length();
                if (wordOffset + wordLen > termLen) {
                    if (wordOffset >= termLen) {
                        wordOffset = wordLen - termLen;
                        if (wordOffset < 0) {
                            wordOffset = 0;
                        }
                    } else {
                        wordLen = termLen - wordOffset;
                    }
                }

                int maxSubwordSize = cfg.maximumSubwordSize;
                int minSubwordSize = cfg.minimumSubwordSize;
                if (wordLen > minSubwordSize) {
                    if (wordLen > maxSubwordSize) {
                        word = word.substring(0, maxSubwordSize);
                        wordLen = maxSubwordSize;
                    }
                    compoundForms.add(word);
                }
            }
        }
        return compoundForms;
    }
}
