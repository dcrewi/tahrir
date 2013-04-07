package tahrir.io.net.microblogging.filters;

import tahrir.io.net.microblogging.microblogs.ParsedMicroblog;

import java.util.SortedSet;

/**
 * A "filter" which lets all microblogs through.
 *
 * @author Kieran Donegan <kdonegan.92@gmail.com>
 */
public class Unfiltered extends MicroblogFilter {
	public Unfiltered(final SortedSet<ParsedMicroblog> initMicroblogs) {
		super();
		initMicroblogStorage(initMicroblogs);
	}

	@Override
	public boolean passesFilter(final ParsedMicroblog mb) {
		return true;
	}
}
