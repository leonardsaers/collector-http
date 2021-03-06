/* Copyright 2010-2013 Norconex Inc.
 * 
 * This file is part of Norconex HTTP Collector.
 * 
 * Norconex HTTP Collector is free software: you can redistribute it and/or 
 * modify it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * Norconex HTTP Collector is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with Norconex HTTP Collector. If not, 
 * see <http://www.gnu.org/licenses/>.
 */
package com.norconex.collector.http.crawler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.norconex.collector.http.db.ICrawlURLDatabase;
import com.norconex.collector.http.filter.IURLFilter;
import com.norconex.collector.http.robot.RobotsMeta;
import com.norconex.collector.http.robot.RobotsTxt;
import com.norconex.collector.http.sitemap.ISitemapsResolver;
import com.norconex.collector.http.sitemap.SitemapURLStore;
import com.norconex.importer.filter.IOnMatchFilter;
import com.norconex.importer.filter.OnMatch;

/**
 * Performs a URL handling logic before actual processing of the document
 * it represents takes place.  That is, before any 
 * document or document header download is
 * Instances are only valid for the scope of a single URL.  
 * @author Pascal Essiembre
 */
/*default*/ final class URLProcessor {

    private static final Logger LOG = 
            LogManager.getLogger(URLProcessor.class);
    
    private static final List<String> SITEMAP_RESOLVED = 
            new ArrayList<String>();

    private final HttpCrawler crawler;
    private final HttpCrawlerConfig config;
    private final List<IHttpCrawlerEventListener> listeners = 
            new ArrayList<IHttpCrawlerEventListener>();
    private final CrawlURL crawlURL;
    private final DefaultHttpClient httpClient;
    private final ICrawlURLDatabase database;
    private RobotsTxt robotsTxt;
    private RobotsMeta robotsMeta;
    private CrawlStatus status;
    
    // Order is important.  E.g. Robots must be after URL Filters and before 
    // Delay resolver
    private final IURLProcessingStep[] defaultSteps = new IURLProcessingStep[] {
        new DepthValidationStep(),
        new URLFiltersStep(),
        new RobotsTxtFiltersStep(),
        new URLNormalizerStep(),
        new SitemapStep(),
        new StoreNextURLStep(),
    };
    private final IURLProcessingStep[] sitemapSteps = new IURLProcessingStep[] {
        new DepthValidationStep(),
        new URLFiltersStep(),
        new RobotsTxtFiltersStep(),
        new URLNormalizerStep(),
        new StoreNextURLStep(),
    };

    /*default*/ URLProcessor(
            HttpCrawler crawler, DefaultHttpClient httpClient, 
            ICrawlURLDatabase database, CrawlURL baseURL) {
        this.crawler = crawler;
        this.httpClient = httpClient;
        this.database = database;
        this.config = crawler.getCrawlerConfig();
        this.crawlURL = baseURL;
        IHttpCrawlerEventListener[] ls = config.getCrawlerListeners();
        if (ls != null) {
            this.listeners.addAll(Arrays.asList(ls));
        }
    }

    public boolean processURL() {
        return processURL(defaultSteps);
    }
    private boolean processSitemapURL() {
        return processURL(sitemapSteps);
    }

    public interface IURLProcessingStep {
        // Returns true to continue to next step
        // Returns false to abort, this URL is rejected.
        boolean processURL();
    }


    //--- URL Depth ------------------------------------------------------------
    private class DepthValidationStep implements IURLProcessingStep {
        @Override
        public boolean processURL() {
            if (config.getMaxDepth() != -1 
                    && crawlURL.getDepth() > config.getMaxDepth()) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("URL too deep to process (" 
                            + crawlURL.getDepth() + "): " + crawlURL.getUrl());
                }
                status = CrawlStatus.TOO_DEEP;
                return false;
            }
            return true;
        }
    }
    
    //--- URL Filters ----------------------------------------------------------
    private class URLFiltersStep implements IURLProcessingStep {
        @Override
        public boolean processURL() {
            if (isURLRejected(config.getURLFilters(), null)) {
                status = CrawlStatus.REJECTED;
                return false;
            }
            return true;
        }
    }
    
    //--- Robots.txt Filters ---------------------------------------------------
    private class RobotsTxtFiltersStep implements IURLProcessingStep {
        @Override
        public boolean processURL() {
            if (!config.isIgnoreRobotsTxt()) {
                robotsTxt = config.getRobotsTxtProvider().getRobotsTxt(
                                httpClient, crawlURL.getUrl());
                if (isURLRejected(robotsTxt.getFilters(), robotsTxt)) {
                    status = CrawlStatus.REJECTED;
                    return false;
                }
            }
            return true;
        }
    }

    //--- Sitemap URL Extraction -----------------------------------------------
    private class SitemapStep implements IURLProcessingStep {
        @Override
        public synchronized boolean processURL() {
            if (config.isIgnoreSitemap()) {
                return true;
            }
            String urlRoot = crawlURL.getUrlRoot();
            boolean resolved = SITEMAP_RESOLVED.contains(urlRoot);
            if (!resolved) {
                resolved = database.isSitemapResolved(urlRoot);
            }
            if (!resolved) {
                ISitemapsResolver sitemapResolver = config.getSitemapResolver();
                String[] robotsTxtLocations = null;
                if (robotsTxt != null) {
                    robotsTxtLocations = robotsTxt.getSitemapLocations();
                }
                SitemapURLStore urlStore = new SitemapURLStore() {
                    private static final long serialVersionUID = 
                            7618470895330355434L;
                    @Override
                    public void add(CrawlURL baseURL) {
                        new URLProcessor(crawler, httpClient, database, baseURL)
                                .processSitemapURL();
                    }
                };
                sitemapResolver.resolveSitemaps(httpClient, urlRoot, 
                        robotsTxtLocations, urlStore);
                database.sitemapResolved(urlRoot);
                SITEMAP_RESOLVED.add(urlRoot);
            }
            return true;
        }
    }
    

    
    //--- URL Normalizer -------------------------------------------------------
    /*
     * Normalize the URL.
     */
    private class URLNormalizerStep implements IURLProcessingStep {
        @Override
        public boolean processURL() {
            if (config.getUrlNormalizer() != null) {
                String url = config.getUrlNormalizer().normalizeURL(
                        crawlURL.getUrl());
                if (url == null) {
                    status = CrawlStatus.REJECTED;
                    return false;
                }
                crawlURL.setUrl(url);
            }
            return true;
        }
    }

    //--- Store Next URLs to process -------------------------------------------
    private class StoreNextURLStep implements IURLProcessingStep {
        @Override
        public boolean processURL() {
            if (robotsMeta != null && robotsMeta.isNofollow()) {
                return true;
            }
            String url = crawlURL.getUrl();
            if (StringUtils.isBlank(url)) {
                return true;
            }
            if (database.isActive(url)) {
                debug("Already being processed: %s", url);
            } else if (database.isQueued(url)) {
                debug("Already queued: %s", url);
            } else if (database.isProcessed(url)) {
                debug("Already processed: %s", url);
            } else {
                database.queue(new CrawlURL(url, crawlURL.getDepth()));
                debug("Queued for processing: %s", url);
            }
            return true;
        }
    }
    
    private static void debug(String message, Object... values) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format(message, values));
        }
    }    

    //=== Utility methods ======================================================
    private boolean isURLRejected(IURLFilter[] filters, RobotsTxt robots) {
        if (filters == null) {
            return false;
        }
        String type = "";
        if (robots != null) {
            type = " (robots.txt)";
        }
        boolean hasIncludes = false;
        boolean atLeastOneIncludeMatch = false;
        for (IURLFilter filter : filters) {
            boolean accepted = filter.acceptURL(crawlURL.getUrl());
            
            // Deal with includes
            if (isIncludeFilter(filter)) {
                hasIncludes = true;
                if (accepted) {
                    atLeastOneIncludeMatch = true;
                }
                continue;
            }

            // Deal with exclude and non-OnMatch filters
            if (accepted) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("ACCEPTED document URL" + type 
                            + ". URL=" + crawlURL.getUrl()
                            + " Filter=" + filter);
                }
            } else {
                fireDocumentRejected(filter, robots, type);
                return true;
            }
        }
        if (hasIncludes && !atLeastOneIncludeMatch) {
            fireDocumentRejected(null, null, " (no include filters matched)");
            return true;
        }
        return false;
    }
    
    private void fireDocumentRejected(
            IURLFilter filter, RobotsTxt robots, String type) {
        for (IHttpCrawlerEventListener listener : listeners) {
            if (robots != null) {
                listener.documentRobotsTxtRejected(
                        crawler, crawlURL.getUrl(), filter, robots);
            } else {
                listener.documentURLRejected(
                        crawler, crawlURL.getUrl(), filter);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("REJECTED document URL" + type + ". URL=" 
                  + crawlURL.getUrl() + " Filter=[no include filters matched]");
        }
    }
    
    private boolean processURL(IURLProcessingStep... steps) {
        try {
            for (int i = 0; i < steps.length; i++) {
                IURLProcessingStep step = steps[i];
                if (!step.processURL()) {
                    return false;
                }
            }
            status = CrawlStatus.OK;
            return true;
        } catch (Exception e) {
            //TODO do we really want to catch anything other than 
            // HTTPFetchException?  In case we want special treatment to the 
            // class?
            status = CrawlStatus.ERROR;
            LOG.error("Could not process URL: " + crawlURL.getUrl(), e);
            return false;
        } finally {
            //--- Mark URL as Processed ----------------------------------------
            if (status != CrawlStatus.OK) {
                if (status == null) {
                    status = CrawlStatus.BAD_STATUS;
                }
                crawlURL.setStatus(status);
                database.processed(crawlURL);
                status.logInfo(crawlURL);
            }
        }
    }

    private boolean isIncludeFilter(IURLFilter filter) {
        return filter instanceof IOnMatchFilter
                && OnMatch.INCLUDE == ((IOnMatchFilter) filter).getOnMatch();
    }
}

