package org.jsoftware.dbpatch.config;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.text.ParseException;

import static org.junit.Assert.assertEquals;

/**
 * @author szalik
 */
public class ConfigurationEntryTest {
    private ConfigurationEntry ce;

    @Before
    public void setUp() throws Exception {
        ce = new ConfigurationEntry("test", new File("."));
        ce.setJdbcUri("test");
        ce.setDriverClass("driver");
    }

    @Test
    public void testAutodetectRollbackDirs1() throws ParseException {
        ce.setPatchDirs("/abc/,/xyz".replace('/', File.separatorChar));
        ce.validate();
        assertEquals("/abc/*.rollback,/xyz/*.rollback".replace('/', File.separatorChar), ce.getRollbackDirs());
    }

    @Test
    public void testAutodetectRollbackDirs2() throws ParseException {
        ce.setPatchDirs("/abc/*.sql".replace('/', File.separatorChar));
        ce.validate();
        assertEquals("/abc/*.rollback".replace('/', File.separatorChar), ce.getRollbackDirs());
    }

    @Test
    public void testAutodetectRollbackDirsChangedDefault() throws ParseException {
        ce.setPatchDirs("/abc/".replace('/', File.separatorChar));
        ce.setRollbackSuffix("*.undo");
        ce.validate();
        assertEquals("/abc/*.undo".replace('/', File.separatorChar), ce.getRollbackDirs());
    }
}
