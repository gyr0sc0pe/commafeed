package com.commafeed.backend.dao;

import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;

import com.commafeed.backend.model.FeedEntryContent;
import com.commafeed.backend.model.FeedEntryContent_;

public class FeedEntryContentDAO extends GenericDAO<FeedEntryContent> {

	public FeedEntryContent findExisting(String content, String title) {
		List<FeedEntryContent> list = findByField(
				FeedEntryContent_.contentHash, DigestUtils.sha1Hex(content));
		for (FeedEntryContent existing : list) {
			if (existing.getContent().length() == content.length()) {
				return existing;
			}
		}
		return null;
	}
}
