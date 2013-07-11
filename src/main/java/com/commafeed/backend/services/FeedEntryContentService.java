package com.commafeed.backend.services;

import javax.ejb.Lock;
import javax.ejb.LockType;
import javax.ejb.Singleton;
import javax.inject.Inject;

import com.commafeed.backend.dao.FeedEntryContentDAO;
import com.commafeed.backend.feeds.FeedUtils;
import com.commafeed.backend.model.FeedEntryContent;

@Singleton
public class FeedEntryContentService {

	@Inject
	FeedEntryContentDAO feedEntryContentDAO;

	@Lock(LockType.WRITE)
	public FeedEntryContent findOrCreate(FeedEntryContent content,
			String baseUrl) {

		FeedEntryContent existing = feedEntryContentDAO.findExisting(
				content.getContent(), content.getTitle());
		if (existing == null) {
			content.setAuthor(FeedUtils.truncate(
					FeedUtils.handleContent(content.getAuthor(), baseUrl, true),
					128));
			content.setTitle(FeedUtils.truncate(
					FeedUtils.handleContent(content.getTitle(), baseUrl, true),
					2048));
			content.setContent(FeedUtils.handleContent(content.getContent(),
					baseUrl, false));
			existing = content;
			feedEntryContentDAO.saveOrUpdate(existing);
		}
		return existing;
	}
}
