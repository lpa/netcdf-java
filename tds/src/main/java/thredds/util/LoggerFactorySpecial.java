package thredds.util;

import org.apache.log4j.*;
import org.slf4j.Logger;
import ucar.nc2.util.log.LoggerFactory;
import ucar.unidata.util.StringUtil2;

import java.io.IOException;

/**
 * A LoggerFactory that uses log4j to create and configure a special RollingFileAppender
 * specific to this name.
 * used by InvDatasetFeatureCollection to create a log for each feature collection.
 *
 * @author caron
 * @since 3/27/13
 */
public class LoggerFactorySpecial implements LoggerFactory {
  private String dir = ".";
  private Level level = Level.INFO;

  public LoggerFactorySpecial(Level lev) {
    dir = System.getProperty("tds.log.dir");
    level = lev;
  }

  @Override
  public Logger getLogger(String name) {
    name = StringUtil2.replace(name.trim(), ' ', "_");
    try {
      //create logger in log4j
      Layout layout = new PatternLayout("%d{yyyy-MM-dd'T'HH:mm:ss.SSS Z} %-5p - %m%n");

      String loggerName = name + ".log";
      RollingFileAppender app = new RollingFileAppender(layout, loggerName);
      app.setMaxBackupIndex(5);            // 5 rollovers
      app.setMaximumFileSize(1000 * 1000); // 1 Mb
      app.setFile(dir + "/" + loggerName);
      app.activateOptions();

      org.apache.log4j.Logger log4j = LogManager.getLogger(name);
      log4j.addAppender(app);
      log4j.setLevel(level);
      log4j.setAdditivity(false); // otherwise, it also gets sent to root logger (threddsServlet.log)

      // get wrapper in slf4j
      return org.slf4j.LoggerFactory.getLogger(name);

    } catch (IOException ioe) {

      // standard slf4j - rely on external configuration
      return org.slf4j.LoggerFactory.getLogger(name);
    }
  }
}
