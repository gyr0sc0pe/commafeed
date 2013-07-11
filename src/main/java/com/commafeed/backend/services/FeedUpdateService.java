package com.commafeed.backend.services;

import java.util.Date;
import java.util.List;

import javax.ejb.Stateless;
import javax.inject.Inject;

import com.commafeed.backend.MetricsBean;
import com.commafeed.backend.cache.CacheService;
import com.commafeed.backend.dao.FeedEntryDAO;
import com.commafeed.backend.dao.FeedEntryStatusDAO;
import com.commafeed.backend.dao.FeedSubscriptionDAO;
import com.commafeed.backend.model.Feed;
import com.commafeed.backend.model.FeedEntry;
import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryStatus;
import com.commafeed.backend.model.FeedSubscription;
import com.commafeed.backend.model.User;
import com.google.common.collect.Lists;

@Stateless
public class FeedUpdateService {

	@Inject
	FeedSubscriptionDAO feedSubscriptionDAO;

	@Inject
	FeedEntryDAO feedEntryDAO;

	@Inject
	FeedEntryStatusDAO feedEntryStatusDAO;

	@Inject
	MetricsBean metricsBean;

	@Inject
	CacheService cache;

	@Inject
	FeedEntryContentService feedEntryContentService;

	public void addEntry(Feed feed, FeedEntry entry,
			List<FeedSubscription> subscriptions) {

		FeedEntry existing = feedEntryDAO.findExisting(entry.getGuid(),
				entry.getUrl(), feed.getId());
		if (existing != null) {
			return;
		}

		FeedEntryContent content = feedEntryContentService.findOrCreate(
				entry.getContent(), feed.getLink());
		entry.setContent(content);
		entry.setInserted(new Date());
		entry.setFeed(feed);

		List<FeedEntryStatus> statuses = Lists.newArrayList();
		List<User> users = Lists.newArrayList();
		for (FeedSubscription sub : subscriptions) {
			FeedEntryStatus status = new FeedEntryStatus();
			status.setEntry(entry);
			status.setSubscription(sub);
			statuses.add(status);

			users.add(sub.getUser());
		}
		cache.invalidateUserData(users.toArray(new User[0]));
		feedEntryDAO.saveOrUpdate(entry);
		feedEntryStatusDAO.saveOrUpdate(statuses);
		metricsBean.entryUpdated(statuses.size());

	}
}
