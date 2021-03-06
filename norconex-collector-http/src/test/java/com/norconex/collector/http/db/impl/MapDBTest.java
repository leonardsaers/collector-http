package com.norconex.collector.http.db.impl;

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

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mapdb.DB;
import org.mapdb.DBMaker;

import com.norconex.collector.http.crawler.CrawlURL;

public class MapDBTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private DB db;

    private Map<String, CrawlURL> map;

    private File dbFile;

    @Before
    public void setUp() throws Exception {
        dbFile = tempFolder.newFile();
        initDB(dbFile);
        map = db.createHashMap("test").keepCounter(true).make();
    }

    private void initDB(File file) throws IOException {
        db = DBMaker.newFileDB(file).make();
    }

    @After
    public void tearDown() throws Exception {
        db.close();
    }

    private CrawlURL buildCrawlURL() {
        CrawlURL c = new CrawlURL("http://www.example.com", 3);
        c.setDocChecksum("docchecksum1111");
        c.setSitemapPriority(0.8f);
        return c;
    }

    /**
     * Simple test that inserts an url into a new map
     * 
     * @throws Exception
     */
    @Test
    public void createDatabaseTest() throws Exception {
        CrawlURL c = buildCrawlURL();
        map.put("url1", c);
        assertEquals(1, map.size());
    }

    /**
     * Test that inserts an url in the map, close the DB, re-open the DB (with
     * same file) and re-open the map and check the content is still there.
     * 
     * @throws Exception
     */
    @Test
    public void loadDatabaseTest() throws Exception {
        // Insert test data
        CrawlURL c = buildCrawlURL();
        map.put("url1", c);

        // Close DB
        db.commit();
        db.close();

        // Re-open DB and map
        initDB(dbFile);
        map = db.getHashMap("test");

        // Check content
        assertEquals(1, map.size());
        c = map.get("url1");
        Assert.assertTrue(0.8f == c.getSitemapPriority());
    }
}
