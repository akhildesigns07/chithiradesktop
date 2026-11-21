package com.chithira.fierydesk.hub;

import com.chithira.fierydesk.core.AppConfig.HubSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** A hub client that answers from memory, for tests: no network. */
class FakeHubClient extends HubClient {

    final List<String> reportedJobs = new ArrayList<>();
    final Deque<HubException> nextReportFailures = new ArrayDeque<>();
    final Deque<CustomerPage> customerPages = new ArrayDeque<>();
    final List<String> customerRequests = new ArrayList<>();

    FakeHubClient() {
        super(new HubSettings(true, "http://hub.test", "chp_aaaaaaaa_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", true, Duration.ofSeconds(1)));
    }

    @Override
    public void reportJob(String json) throws HubException {
        HubException failure = nextReportFailures.poll();
        if (failure != null) throw failure;
        reportedJobs.add(json);
    }

    @Override
    public CustomerPage customers(String updatedSince) {
        customerRequests.add(String.valueOf(updatedSince));
        return customerPages.poll();
    }
}
