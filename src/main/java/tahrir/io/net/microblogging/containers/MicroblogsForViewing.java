package tahrir.io.net.microblogging.containers;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tahrir.TrConstants;
import tahrir.io.net.microblogging.ContactBook;
import tahrir.io.net.microblogging.microblogs.ParsedMicroblog;
import tahrir.tools.TrUtils;

import java.util.Collections;
import java.util.Comparator;
import java.util.SortedSet;

/**
 * Stores a collection of microblogs for viewing purposes, normally to display in the GUI.
 *
 * @author Kieran Donegan <kdonegan.92@gmail.com>
 */
public class MicroblogsForViewing {
	private static Logger logger = LoggerFactory.getLogger(MicroblogsForViewing.class);

	private final SortedSet<ParsedMicroblog> parsedMicroblogs;
	private final ParsedMicroblogTimeComparator comparator;

	private final ContactBook contactBook;

	public MicroblogsForViewing(final ContactBook contactBook) {
		this.contactBook = contactBook;
		comparator = new ParsedMicroblogTimeComparator();
		parsedMicroblogs = Collections.synchronizedSortedSet(Sets.newTreeSet(comparator));
	}

	// synchronised to ensure that size of set is checked properly
	public synchronized boolean insert(final ParsedMicroblog mb) {
		// the microblog might not be added
		boolean inserted = false;

		if (!isFull()) {
			addToParsed(mb);
			inserted = true;
		} else if (shouldAddByReplacement(mb)) {
			// make room
			removeFromParsed(parsedMicroblogs.last());
			addToParsed(mb);
			inserted = true;
			logger.info("Adding a microblog for viewing by replacement");
		}
		return inserted;
	}

	public SortedSet<ParsedMicroblog> getMicroblogSet() {
		return parsedMicroblogs;
	}

	private void removeFromParsed(final ParsedMicroblog mb) {
		parsedMicroblogs.remove(mb);
		// also needs to be removed from listeners (filters) to avoid wasting memory
		TrUtils.eventBus.post(new MicroblogRemovalEvent(mb));
	}

	private void addToParsed(final ParsedMicroblog mb) {
		parsedMicroblogs.add(mb);
		// post event to listeners i.e filters
		TrUtils.eventBus.post(new MicroblogAddedEvent(mb));
	}

	private boolean shouldAddByReplacement(final ParsedMicroblog mb) {
		return contactBook.hasContact(mb.getMbData().getAuthorPubKey()) || isNewerThanLast(mb);
	}

	private boolean isNewerThanLast(final ParsedMicroblog mb) {
		return comparator.compare(mb, parsedMicroblogs.last()) < 0;
	}

	private boolean isFull() {
		return parsedMicroblogs.size() > TrConstants.MAX_MICROBLOGS_FOR_VIEWING;
	}

	public static class ParsedMicroblogTimeComparator implements Comparator<ParsedMicroblog> {
		@Override
		public int compare(final ParsedMicroblog mb1, final ParsedMicroblog mb2) {
			return Double.compare(mb2.getMbData().getTimeCreated(), mb1.getMbData().getTimeCreated());
		}
	}

	public static class MicroblogAddedEvent {
		public ParsedMicroblog mb;

		public MicroblogAddedEvent(final ParsedMicroblog mb) {
			this.mb = mb;
		}
	}

	public static class MicroblogRemovalEvent {
		public ParsedMicroblog mb;

		public MicroblogRemovalEvent(final ParsedMicroblog mb) {
			this.mb = mb;
		}
	}
}