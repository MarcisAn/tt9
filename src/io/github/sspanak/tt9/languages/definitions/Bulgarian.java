package io.github.sspanak.tt9.languages.definitions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

import io.github.sspanak.tt9.R;
import io.github.sspanak.tt9.languages.Language;
import io.github.sspanak.tt9.languages.Punctuation;

public class Bulgarian extends Language {
	public Bulgarian() {
		id = 7;
		name = "български";
		locale = new Locale("bg","BG");
		dictionaryFile = "bg-utf8.txt";
		isPunctuationPartOfWords = false;
		icon = R.drawable.ime_lang_bg;
		abcLowerCaseIcon = R.drawable.ime_lang_cyrillic_lower;
		abcUpperCaseIcon = R.drawable.ime_lang_cyrillic_upper;

		characterMap = new ArrayList<>(Arrays.asList(
			Punctuation.Secondary, // 0
			Punctuation.Main, // 1
			new ArrayList<>(Arrays.asList("а", "б", "в", "г")), // 2
			new ArrayList<>(Arrays.asList("д", "е", "ж", "з")), // 3
			new ArrayList<>(Arrays.asList("и", "й", "к", "л", "ѝ")), // 4
			new ArrayList<>(Arrays.asList("м", "н", "о", "п")), // 5
			new ArrayList<>(Arrays.asList("р", "с", "т", "у")), // 6
			new ArrayList<>(Arrays.asList("ф", "х", "ц", "ч")), // 7
			new ArrayList<>(Arrays.asList("ш", "щ", "ъ")), // 8
			new ArrayList<>(Arrays.asList("ь", "ю", "я")) // 9
		));
	}
}