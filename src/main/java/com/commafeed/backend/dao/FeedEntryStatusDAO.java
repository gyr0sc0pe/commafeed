package com.commafeed.backend.dao;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.ejb.Stateless;
import javax.inject.Inject;
import javax.persistence.Query;
import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Selection;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.lambdaj.Lambda;

import com.commafeed.backend.FixedSizeSortedSet;
import com.commafeed.backend.model.Feed;
import com.commafeed.backend.model.FeedEntry;
import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryContent_;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedEntryStatus_;
import com.commafeed.backend.model.FeedEntry_;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.FeedSubscription_;
import com.commafeed.backend.model.Feed_;
import com.commafeed.backend.model.Models;
import com.commafeed.backend.model.User;
import com.commafeed.backend.model.UserSettings.ReadingOrder;
import com.commafeed.backend.services.ApplicationSettingsService;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@Stateless
public class FeedEntryStatusDAO extends GenericDAO<FeedEntryStatus> {

	protected static Logger log = LoggerFactory
			.getLogger(FeedEntryStatusDAO.class);

	private static final Comparator<FeedEntry> COMPARATOR = new Comparator<FeedEntry>() {
		@Override
		public int compare(FeedEntry o1, FeedEntry o2) {
			return o2.getUpdated().compareTo(o1.getUpdated());
		};
	};

	@Inject
	ApplicationSettingsService applicationSettingsService;

	public FeedEntryStatus getStatus(FeedSubscription sub, FeedEntry entry) {

		CriteriaQuery<FeedEntryStatus> query = builder.createQuery(getType());
		Root<FeedEntryStatus> root = query.from(getType());

		Predicate p1 = builder.equal(root.get(FeedEntryStatus_.entry), entry);
		Predicate p2 = builder.equal(root.get(FeedEntryStatus_.subscription), sub);

		query.where(p1, p2);

		List<FeedEntryStatus> statuses = em.createQuery(query).getResultList();
		FeedEntryStatus status = Iterables.getFirst(statuses, null);
		if (status == null) {
			status = new FeedEntryStatus(sub.getUser(), sub, entry);
			status.setRead(true);
		}
		return status;
	}

	public List<FeedEntryStatus> findByKeywords(User user, String keywords,
			int offset, int limit) {

		String joinedKeywords = StringUtils.join(
				keywords.toLowerCase().split(" "), "%");
		joinedKeywords = "%" + joinedKeywords + "%";

		CriteriaQuery<Tuple> query = builder.createTupleQuery();
		Root<FeedEntry> root = query.from(FeedEntry.class);

		Join<FeedEntry, Feed> feedJoin = root.join(FeedEntry_.feed);
		Join<Feed, FeedSubscription> subJoin = feedJoin
				.join(Feed_.subscriptions);
		Join<FeedEntry, FeedEntryContent> contentJoin = root
				.join(FeedEntry_.content);

		Selection<FeedEntry> entryAlias = root.alias("entry");
		Selection<FeedSubscription> subAlias = subJoin.alias("subscription");
		query.multiselect(entryAlias, subAlias);

		List<Predicate> predicates = Lists.newArrayList();

		predicates
				.add(builder.equal(subJoin.get(FeedSubscription_.user), user));

		Predicate content = builder.like(
				builder.lower(contentJoin.get(FeedEntryContent_.content)),
				joinedKeywords);
		Predicate title = builder.like(
				builder.lower(contentJoin.get(FeedEntryContent_.title)),
				joinedKeywords);
		predicates.add(builder.or(content, title));

		query.where(predicates.toArray(new Predicate[0]));
		orderEntriesBy(query, root, ReadingOrder.desc);

		TypedQuery<Tuple> q = em.createQuery(query);
		limit(q, offset, limit);
		setTimeout(q);

		List<Tuple> list = q.getResultList();
		List<FeedEntryStatus> results = Lists.newArrayList();
		for (Tuple tuple : list) {
			FeedEntry entry = tuple.get(entryAlias);
			FeedSubscription subscription = tuple.get(subAlias);

			FeedEntryStatus status = getStatus(subscription, entry);
			if (status == null) {
				status = new FeedEntryStatus(user, subscription, entry);
				status.setRead(true);
				status.setSubscription(subscription);
			}
			results.add(status);
		}

		return lazyLoadContent(true, results);
	}

	public List<FeedEntryStatus> findStarred(User user, ReadingOrder order,
			boolean includeContent) {
		return findStarred(user, null, -1, -1, order, includeContent);
	}

	public List<FeedEntryStatus> findStarred(User user, Date newerThan,
			int offset, int limit, ReadingOrder order, boolean includeContent) {

		CriteriaQuery<FeedEntryStatus> query = builder.createQuery(getType());
		Root<FeedEntryStatus> root = query.from(getType());

		List<Predicate> predicates = Lists.newArrayList();

		predicates
				.add(builder.equal(root.get(FeedEntryStatus_.user), user));
		predicates.add(builder.equal(root.get(FeedEntryStatus_.starred), true));
		query.where(predicates.toArray(new Predicate[0]));

		if (newerThan != null) {
			predicates.add(builder.greaterThanOrEqualTo(
					root.get(FeedEntryStatus_.entryInserted), newerThan));
		}

		orderStatusesBy(query, root, order);

		TypedQuery<FeedEntryStatus> q = em.createQuery(query);
		limit(q, offset, limit);
		setTimeout(q);
		return lazyLoadContent(includeContent, q.getResultList());
	}

	public List<FeedEntryStatus> findBySubscriptions(User user, List<FeedSubscription> subscriptions, Date newerThan,
			ReadingOrder order, boolean includeContent) {
		return findBySubscriptions(user, subscriptions, newerThan, -1, -1, order, includeContent);
	}

	public List<FeedEntryStatus> findBySubscriptions(User user, List<FeedSubscription> subscriptions, Date newerThan, int offset,
			int limit, ReadingOrder order, boolean includeContent) {

		int capacity = offset + limit;
		FixedSizeSortedSet<FeedEntry> set = new FixedSizeSortedSet<FeedEntry>(capacity < 0 ? Integer.MAX_VALUE : capacity, COMPARATOR);
		for (FeedSubscription sub : subscriptions) {
			CriteriaQuery<FeedEntry> query = builder.createQuery(FeedEntry.class);
			Root<FeedEntry> root = query.from(FeedEntry.class);

			List<Predicate> predicates = Lists.newArrayList();
			predicates.add(builder.equal(root.get(FeedEntry_.feed), sub.getFeed()));

			if (newerThan != null) {
				predicates.add(builder.greaterThanOrEqualTo(
						root.get(FeedEntry_.inserted), newerThan));
			}
			query.where(predicates.toArray(new Predicate[0]));
			orderEntriesBy(query, root, order);
			TypedQuery<FeedEntry> q = em.createQuery(query);
			limit(q, 0, capacity);
			setTimeout(q);

			List<FeedEntry> list = q.getResultList();
			Lambda.forEach(list).setSubscription(sub);
			set.addAll(list);
		}

		List<FeedEntry> entries = set.asList();
		int size = entries.size();
		if (size < offset) {
			return Lists.newArrayList();
		}

		entries = entries.subList(offset, size);

		List<FeedEntryStatus> results = Lists.newArrayList();
		for (FeedEntry entry : entries) {
			FeedSubscription subscription = entry.getSubscription();
			results.add(getStatus(subscription, entry));
		}

		return lazyLoadContent(includeContent, results);
	}

	public List<FeedEntryStatus> findUnreadBySubscriptions(User user, List<FeedSubscription> subscriptions, Date newerThan,
			ReadingOrder order, boolean includeContent) {
		return findUnreadBySubscriptions(user, subscriptions, newerThan, -1, -1, order, includeContent);
	}

	public List<FeedEntryStatus> findUnreadBySubscriptions(User user, List<FeedSubscription> subscriptions, Date newerThan, int offset,
			int limit, ReadingOrder order, boolean includeContent) {

		int capacity = offset + limit;
		FixedSizeSortedSet<FeedEntry> set = new FixedSizeSortedSet<FeedEntry>(capacity < 0 ? Integer.MAX_VALUE : capacity, COMPARATOR);
		for (FeedSubscription sub : subscriptions) {
			CriteriaQuery<FeedEntryStatus> query = builder.createQuery(getType());
			Root<FeedEntryStatus> root = query.from(getType());

			List<Predicate> predicates = Lists.newArrayList();

			predicates.add(builder.equal(root.get(FeedEntryStatus_.subscription), sub));
			predicates.add(builder.isFalse(root.get(FeedEntryStatus_.read)));

			if (newerThan != null) {
				predicates.add(builder.greaterThanOrEqualTo(
						root.get(FeedEntryStatus_.entryInserted), newerThan));
			}

			query.where(predicates.toArray(new Predicate[0]));
			orderStatusesBy(query, root, order);

			TypedQuery<FeedEntryStatus> q = em.createQuery(query);
			limit(q, offset, limit);
			setTimeout(q);
		}

		List<FeedEntry> entries = set.asList();
		int size = entries.size();
		if (size < offset) {
			return Lists.newArrayList();
		}

		entries = entries.subList(offset, size);

		List<FeedEntryStatus> results = Lists.newArrayList();
		for (FeedEntry entry : entries) {
			FeedSubscription subscription = entry.getSubscription();
			results.add(getStatus(subscription, entry));
		}

		return lazyLoadContent(includeContent, results);
	}

	private List<FeedEntryStatus> lazyLoadContent(boolean includeContent,
			List<FeedEntryStatus> results) {
		if (includeContent) {
			for (FeedEntryStatus status : results) {
				Models.initialize(status.getSubscription().getFeed());
				Models.initialize(status.getEntry().getContent());
			}
		}
		return results;
	}

	private void orderEntriesBy(CriteriaQuery<?> query, Path<FeedEntry> entryJoin,
			ReadingOrder order) {
		orderBy(query, entryJoin.get(FeedEntry_.updated), order);
	}

	private void orderStatusesBy(CriteriaQuery<?> query, Path<FeedEntryStatus> statusJoin,
			ReadingOrder order) {
		orderBy(query, statusJoin.get(FeedEntryStatus_.entryUpdated), order);
	}

	private void orderBy(CriteriaQuery<?> query, Path<Date> date,
			ReadingOrder order) {
		if (order != null) {
			if (order == ReadingOrder.asc) {
				query.orderBy(builder.asc(date));
			} else {
				query.orderBy(builder.desc(date));
			}
		}
	}

	protected void setTimeout(Query query) {
		setTimeout(query, applicationSettingsService.get().getQueryTimeout());
	}

	/**
	 * Map between subscriptionId and unread count
	 */
	@SuppressWarnings("rawtypes")
	public Map<Long, Long> getUnreadCount(User user) {
		Map<Long, Long> map = Maps.newHashMap();
		Query query = em.createNamedQuery("EntryStatus.unreadCounts");
		query.setParameter("user", user);
		setTimeout(query);
		List resultList = query.getResultList();
		for (Object o : resultList) {
			Object[] array = (Object[]) o;
			map.put((Long) array[0], (Long) array[1]);
		}
		return map;
	}

	public void markSubscriptionEntries(User user, List<FeedSubscription> subscriptions,
			Date olderThan) {
		List<FeedEntryStatus> statuses = findUnreadBySubscriptions(user, subscriptions, null,
				null, false);
		markList(statuses, olderThan);
	}

	public void markStarredEntries(User user, Date olderThan) {
		List<FeedEntryStatus> statuses = findStarred(user, null, false);
		markList(statuses, olderThan);
	}

	private void markList(List<FeedEntryStatus> statuses, Date olderThan) {
		List<FeedEntryStatus> list = Lists.newArrayList();
		for (FeedEntryStatus status : statuses) {
			if (!status.isRead()) {
				Date inserted = status.getEntry().getInserted();
				if (olderThan == null || inserted == null
						|| olderThan.after(inserted)) {
					if (status.isStarred()) {
						status.setRead(true);
						list.add(status);
					} else {
						delete(status);
					}

				}
			}
		}
		saveOrUpdate(list);
	}

}
