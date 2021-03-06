package net.bible.service.device.speak;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;

import net.bible.service.common.CommonUtils;

import android.content.SharedPreferences;
import android.util.Log;

/** Keep track of a list of chunks of text being fed to TTS
 * 
 * @author Martin Denham [mjdenham at gmail dot com]
 * @see gnu.lgpl.License for license details.<br>
 *      The copyright to this program is held by it's author.
 */
public class SpeakTextProvider {

    private List<String> mTextToSpeak = new ArrayList<String>();
    private int nextTextToSpeak = 0;
    // this fraction supports pause/rew/ff; if o then speech occurs normally, if 0.5 then next speech chunk is half completed...
    private float fractionOfNextSentenceSpoken = 0;
    
    // Before ICS Android would split up long text for you but since ICS this error occurs:
	//    if (mText.length() >= MAX_SPEECH_ITEM_CHAR_LENGTH) {
	//        Log.w(TAG, "Text too long: " + mText.length() + " chars");
    private static final int MAX_SPEECH_ITEM_CHAR_LENGTH = 4000;
    
    // require DOTALL to allow . to match new lines which occur in books like JOChrist
	private static Pattern BREAK_PATTERN = Pattern.compile(".{100,2000}[a-z]+[.?!][\\s]{1,}+", Pattern.DOTALL);
	
	private static class StartPos {
		boolean found = false;
		private int startPosition = 0;
		private String text = "";
		private float actualFractionOfWhole = 1;
	}
	
	// enable state to be persisted if paused for a long time
	private static final String PERSIST_SPEAK_TEXT = "SpeakText";
	private static final String PERSIST_SPEAK_TEXT_SEPARATOR = "XXSEPXX";
	private static final String PERSIST_NEXT_TEXT = "NextText";
	private static final String PERSIST_FRACTION_SPOKEN = "FractionSpoken";
	
	private static final String TAG = "Speak";

	public void addTextsToSpeak(List<String> textsToSpeak) {
		for (String text : textsToSpeak) {
	   		this.mTextToSpeak.addAll(breakUpText(text));
		}
    	Log.d(TAG, "Total Num blocks in speak queue:"+mTextToSpeak.size());
	}
	
	public boolean isMoreTextToSpeak() {
		//TODO: there seems to be an occasional problem when using ff/rew/pause in the last chunk
		return nextTextToSpeak<mTextToSpeak.size();
	}

	public String getNextTextToSpeak() {
        String text = getNextTextChunk();
        
        // if a pause occurred then skip the first part
        if (fractionOfNextSentenceSpoken>0) {
        	Log.d(TAG, "Getting part of text to read.  Fraction:"+fractionOfNextSentenceSpoken);

        	StartPos textFraction = getPrevTextStartPos(text, fractionOfNextSentenceSpoken);
        	if (textFraction.found) {
	        	fractionOfNextSentenceSpoken = textFraction.actualFractionOfWhole;
	        	text = textFraction.text;
        	} else {
        		Log.e(TAG, "Eror finding next text. fraction:"+fractionOfNextSentenceSpoken);
        		// try to prevent recurrence of error, but do not say anything
        		fractionOfNextSentenceSpoken = 0;
        		text = "";
        	}
        }
        return text;		
	}

	private String getNextTextChunk() {
		String text = peekNextTextChunk();
		nextTextToSpeak++;
        return text;
	}
	private String peekNextTextChunk() {
		if (!isMoreTextToSpeak()) {
			Log.e(TAG, "Error: passed end of Speaktext.  nextText:"+nextTextToSpeak+" textToSpeak size:"+mTextToSpeak.size());
			return "";
		}
        return mTextToSpeak.get(nextTextToSpeak);
	}
	
	/** fractionCompleted may be a fraction of a fraction of the current block if this is not the first pause in this block
	 * 
	 * @param fractionCompleted of last block of text returned by getNextTextToSpeak
	 */
	public void pause(float fractionCompleted) {
		Log.d(TAG, "Pause CurrentSentence:"+nextTextToSpeak);

        // accumulate these fractions until we reach the end of a chunk of text
        // if pause several times the fraction of text completed becomes a fraction of the fraction left i.e. 1-previousFractionCompleted
        // also ensure the fraction is never greater than 1/all text
        fractionOfNextSentenceSpoken += Math.min(1, 
        								((1.0-fractionOfNextSentenceSpoken)*fractionCompleted));
        Log.d(TAG, "Fraction of current sentence spoken:"+fractionOfNextSentenceSpoken);

        backOneChunk();
	}
	
	public void rewind() {
		// go back to start of current sentence
    	StartPos textFraction = getPrevTextStartPos(peekNextTextChunk(), fractionOfNextSentenceSpoken);

    	// if could not find a previous sentence end
    	if (!textFraction.found) {
    		if (backOneChunk()) {
    			textFraction = getPrevTextStartPos(peekNextTextChunk(), 1.0f);
    		}
		} else {
			// go back a little bit further in the current chunk
			StartPos extraFraction = getPrevTextStartPos(peekNextTextChunk(), getStartPosFraction(textFraction.startPosition, peekNextTextChunk()));
			if (extraFraction.found) {
				textFraction = extraFraction;
			}
		}
    	
    	if (textFraction.found) {
	    	fractionOfNextSentenceSpoken = textFraction.actualFractionOfWhole;
    	} else {
        	Log.e(TAG, "Could not rewind");
    	}

    	Log.d(TAG, "Rewind chunk length start position:"+fractionOfNextSentenceSpoken);
	}

	public void forward() {
		Log.d(TAG, "Forward nextText:"+nextTextToSpeak);

		// go back to start of current sentence
    	StartPos textFraction = getForwardTextStartPos(peekNextTextChunk(), fractionOfNextSentenceSpoken);

    	// if could not find the next sentence start
    	if (!textFraction.found && forwardOneChunk()) {
	    	textFraction = getForwardTextStartPos(peekNextTextChunk(), 0.0f);   		
		}
    	
    	if (textFraction.found) {
	    	fractionOfNextSentenceSpoken = textFraction.actualFractionOfWhole;
    	} else {
        	Log.e(TAG, "Could not forward");
    	}

    	Log.d(TAG, "Forward chunk length start position:"+fractionOfNextSentenceSpoken);
	}

	public void finishedUtterance(String utteranceId) {
		// reset pause info as a chunk is now finished and it may have been started using continue
		fractionOfNextSentenceSpoken = 0;
	}
	
	/** current chunk needs to be re-read (at least a fraction of it after pause)
	 */
	private boolean backOneChunk() {
		if (nextTextToSpeak > 0) {
			nextTextToSpeak--;
			return true;
		} else {
			return false;
		}
	}
	
	/** current chunk needs to be re-read (at least a fraction of it after pause)
	 */
	private boolean forwardOneChunk() {
		if (nextTextToSpeak < mTextToSpeak.size()-1) {
			nextTextToSpeak++;
			return true;
		} else {
			return false;
		}
	}

	public void reset() {
    	if (mTextToSpeak!=null) {
    		mTextToSpeak.clear();
    	}
		nextTextToSpeak = 0;
		fractionOfNextSentenceSpoken = 0;
	}
	
	/** save state to allow long pauses
	 */
	public void persistState() {
		if (mTextToSpeak.size()>0) {
			CommonUtils.getSharedPreferences()
						.edit()
						.putString(PERSIST_SPEAK_TEXT, StringUtils.join(mTextToSpeak, PERSIST_SPEAK_TEXT_SEPARATOR))
						.putInt(PERSIST_NEXT_TEXT, nextTextToSpeak)
						.putFloat(PERSIST_FRACTION_SPOKEN, fractionOfNextSentenceSpoken)
						.commit();
		}
	}

	/** restore state to allow long pauses
	 * 
	 * @return state restored
	 */
	public boolean restoreState() {
		boolean isRestored = false;
		SharedPreferences sharedPreferences = CommonUtils.getSharedPreferences();
		if (sharedPreferences.contains(PERSIST_SPEAK_TEXT)) {
			mTextToSpeak = new ArrayList<String>(Arrays.asList(sharedPreferences.getString(PERSIST_SPEAK_TEXT, "").split(PERSIST_SPEAK_TEXT_SEPARATOR)));
			nextTextToSpeak = sharedPreferences.getInt(PERSIST_NEXT_TEXT, 0);
			fractionOfNextSentenceSpoken = sharedPreferences.getFloat(PERSIST_FRACTION_SPOKEN, 0);
			clearPersistedState();
			isRestored = true;
		}
		
		return isRestored;
	}
	public void clearPersistedState() {
		CommonUtils.getSharedPreferences().edit().remove(PERSIST_SPEAK_TEXT)
												.remove(PERSIST_NEXT_TEXT)
												.remove(PERSIST_FRACTION_SPOKEN)
												.commit();
	}

	private StartPos getPrevTextStartPos(String text, float fraction) {
		StartPos retVal = new StartPos();
		
    	int allTextLength = text.length();
    	int nextTextOffset = (int)(Math.min(1,fraction)*allTextLength);
    	
    	BreakIterator breakIterator = BreakIterator.getSentenceInstance();
    	breakIterator.setText(text);
    	int startPos = 0;
    	try {
    		// this can rarely throw an Exception
    		startPos = breakIterator.preceding(nextTextOffset);
    	} catch (Exception e) {
    		Log.e(TAG, "Error finding previous sentence start", e);
    	}
    	retVal.found = startPos>=0;
    	
    	if (retVal.found) {
        	retVal.startPosition = startPos; 
    		
	    	// because we don't return an exact fraction, but go to the beginning of a sentence, we need to update the fractionAlreadySpoken  
	    	retVal.actualFractionOfWhole = ((float)retVal.startPosition)/allTextLength;
	    	
	    	retVal.text = text.substring(retVal.startPosition);
    	}

    	return retVal;
	}
	
	private StartPos getForwardTextStartPos(String text, float fraction) {
		StartPos retVal = new StartPos();
		
    	int allTextLength = text.length();
    	int nextTextOffset = (int)(Math.min(1,fraction)*allTextLength);
    	
    	BreakIterator breakIterator = BreakIterator.getSentenceInstance();
    	breakIterator.setText(text);
    	int startPos = 0; 
    	try {
    		// this can rarely throw an Exception
    		startPos = breakIterator.following(nextTextOffset);
    	} catch (Exception e) {
    		Log.e(TAG, "Error finding next sentence start", e);
    	}
    	retVal.found = startPos>=0;
    	
    	if (retVal.found) {
    		// nudge the startPos past the beginning of sentence so this sentence start is found when searching for previous block in getNextSentence
        	retVal.startPosition = startPos<text.length()-1-1? startPos+1 : startPos;
        	
	    	// because we don't return an exact fraction, but go to the beginning of a sentence, we need to update the fractionAlreadySpoken  
	    	retVal.actualFractionOfWhole = ((float)retVal.startPosition)/allTextLength;
	    	
	    	retVal.text = text.substring(retVal.startPosition);
    	}
    	return retVal;
	}

	/** ICS rejects text longer than 4000 chars so break it up
	 * 
	 */
	private List<String> breakUpText(String text) {
		//
		// first try to split text nicely at the end of sentences
		//
		List<String> chunks1 = new ArrayList<String>();
		
		// is the text short enough to use as is
		if (text.length()<MAX_SPEECH_ITEM_CHAR_LENGTH) {
			chunks1.add(text);
		} else {
			// break up the text at sentence ends
			Matcher matcher = BREAK_PATTERN.matcher(text);
	
			int matchedUpTo = 0;
			while (matcher.find()) {
				int nextEnd = matcher.end();
				chunks1.add(text.substring(matchedUpTo, nextEnd));
				matchedUpTo = nextEnd;
			}
			
			// add on the final part of the text, if there is any
			if (matchedUpTo < text.length()) {
				chunks1.add(text.substring(matchedUpTo));
			}
		}
		
		//
		// If any text is still too long because the regexp was not matched then forcefully split it up
		// All chunks are probably now less than 4000 chars as required by tts but go through again for languages that don't have '. ' at the end of sentences
		//
		List<String> chunks2 = new ArrayList<String>();
		for (String chunk : chunks1) {
			if (chunk.length()<MAX_SPEECH_ITEM_CHAR_LENGTH) {
				chunks2.add(chunk);
			} else {
				// force chunks to be correct length -10 is just to allow a bit of extra room
				chunks2.addAll(splitEqually(chunk, MAX_SPEECH_ITEM_CHAR_LENGTH-10));
			}
		}

		return chunks2;
	}
	
	private List<String> splitEqually(String text, int size) {
	    // Give the list the right capacity to start with. You could use an array instead if you wanted.
	    List<String> ret = new ArrayList<String>((text.length() + size - 1) / size);

	    for (int start = 0; start < text.length(); start += size) {
	        ret.add(text.substring(start, Math.min(text.length(), start + size)));
	    }
	    return ret;
	}
	
	private float getStartPosFraction(int startPos, String text) {
		float startFraction = ((float)startPos)/text.length();
		
		// ensure fraction is between 0 and 1
		startFraction = Math.max(0, startFraction);
		startFraction = Math.min(1, startFraction);
		
		return startFraction;
	}
	
	public long getTotalChars() {
		long totChars = 0;
		for (String chunk: mTextToSpeak) {
			totChars += chunk.length();
		}
		return totChars;
	}
	/** this relies on fraction which is set at pause
	 */
	public long getSpokenChars() {
		long spokenChars = 0;
		if (mTextToSpeak.size()>0) {
			for (int i=0; i<nextTextToSpeak-1; i++) {
				String chunk = mTextToSpeak.get(i);
				spokenChars += chunk.length();
			}
			
			if (nextTextToSpeak<mTextToSpeak.size()) {
				spokenChars += fractionOfNextSentenceSpoken * (float)mTextToSpeak.get(nextTextToSpeak).length();
			}
		}		
		return spokenChars;
	}
	
//	private List<String> nonREbreakUpText(String text) {
//		List<String> chunks = new ArrayList<String>();
//
//		int matchedUpTo = 0;
//		int count = 0;
//		while (text.length()-matchedUpTo>1000) {
//			int nextEnd = text.indexOf(". ",matchedUpTo+100)+2;
//			if (nextEnd!=-1) {
//				Log.d(TAG, "Match "+(++count)+" from "+matchedUpTo+" to "+nextEnd);
//				chunks.add(text.substring(matchedUpTo, nextEnd));
//				matchedUpTo = nextEnd;
//			}
//		}
//		// add on the final part of the text
//		chunks.add(text.substring(matchedUpTo));
//
//		return chunks;
//	}
//
}
