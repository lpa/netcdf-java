/*
 * Copyright (c) 1998 - 2011. University Corporation for Atmospheric Research/Unidata
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.nc2.grib;

/**
 * Abstraction of GribTable for Grib Collections.
 * Still figuring out what the minimal need is
 *
 * @author John
 * @since 9/5/11
 */
public interface GribTables {
  //public static final int MISSING = -9999;   // LOOK ?? from Grib2PDS
  //public static final double MISSINGD = -9999.0;

  String getLevelNameShort(int code);

  String getSubCenterName(int center, int subcenter);

  GribStatType getStatType(int intvType);

  VertCoord.VertUnit getVertUnit(int code);

  /* Parameter getParameter(int discipline, int category, int parameter);

  String getIntervalNameShort(int intvType);

  String getProbabilityNameShort(int ensDerivedType);

  String getTableValue(String s, int intvType);

  CF.CellMethods convertTable4_10(int intvType);

  String getVariableName(Grib2Record gr);
  String getVariableName(Grib1Record gr);
  String getVariableName(int discipline, int category, int parameter);

  CalendarDate getForecastDate(Grib2Record gr);
  CalendarDate getForecastDate(Grib1Record gr);

  int[] getForecastTimeInterval(Grib2Record gr);
  int[] getForecastTimeInterval(Grib1Record gr);

  boolean isLatLon2D(int template, int center);

  boolean isLatLon(int template, int center);

  VertCoord.VertUnit getLevelUnit(int code);

  LatLonCoordType getLatLon2DcoordType(String desc);

  public enum LatLonCoordType {U, V, P}  */

  public static interface Parameter {
    public int getDiscipline();

    public int getCategory();

    public int getNumber();

    public String getName();

    public String getUnit();

    public String getAbbrev();

    public String getDescription();

    public String getId();
  }


}
