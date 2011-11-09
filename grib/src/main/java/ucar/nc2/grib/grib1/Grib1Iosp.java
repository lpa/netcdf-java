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

package ucar.nc2.grib.grib1;

import thredds.inventory.CollectionManager;
import ucar.ma2.*;
import ucar.nc2.*;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants.CF;
import ucar.nc2.constants._Coordinate;
import ucar.nc2.grib.*;
import ucar.nc2.iosp.AbstractIOServiceProvider;
import ucar.nc2.util.CancelTask;
import ucar.nc2.wmo.CommonCodeTable;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.Parameter;
import ucar.unidata.util.StringUtil2;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Formatter;
import java.util.List;

/**
 * IOSP for GRIB1 collections
 *
 * @author John
 * @since 9/5/11
 */
public class Grib1Iosp extends AbstractIOServiceProvider {
  static private final float MISSING_VALUE = Float.NaN;
  static private final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Grib1Iosp.class);
  static private final boolean debugTime = false, debugRead = false;

 /*
  http://www.ncl.ucar.edu/Document/Manuals/Ref_Manual/NclFormatSupport.shtml#GRIB
  The following section gives the algorithm NCL uses to assign names to GRIB1 variables.

  GRIB1 data variable name encoding:

    if entry matching parameter table version and parameter number is found (either in built-in or user-supplied table)
      and entry contains a short name for the parameter:
        if recognized as probability product:
          <probability_parameter_short_name>_<subject_variable_short_name> (ex: PROB_A_PCP)
        else:
          <parameter_short_name> (ex: TMP)
    else:
       VAR_<parameter_number> (ex: VAR_179)

    if pre-defined grid:
       _<pre-defined_grid_number> (ex: TMP_6)
    else if grid defined in GDS (Grid Description Section):
       _GDS<grid_type_number> (ex: TMP_GDS4)

    _<level_type_abbreviation> (ex: TMP_GDS4_ISBL)

    if not statistically processed variable and not duplicate name the name is complete at this point.

    if statistically-processed variable with constant specified statistical processing duration:
          _<statistical_processing_type_abbreviation><statistical_processing_duration><duration_units> (ex: ACPCP_44_SFC_acc6h)
    else if statistically-processed variable with no specified processing duration
       _<statistical_processing_type_abbreviation> (ex: A_PCP_192_SFC_acc)

    if variable name is duplicate of existing variable name (this should not normally occur):
       _n (where n begins with 1 for first duplicate) (ex: TMP_GDS4_ISBL_1)

Notes:
  * Probability products are properly recognized in version 4.3.0 or later.
  * NCL uses the generic construction VAR_<parameter_number> in two situations:
    - The entry in the applicable published table contains no short name suitable for use as a component of an NCL variable name.
      Users should expect that later revisions to the table may result in the parameter receiving a short name, causing the name to change.
    - There is no recognized entry for the parameter number. In this case, NCL outputs a warning message. The parameter index
      could be unrecognized for several reasons:
        > No parameter table has been supplied for the originating center and the index is greater than 127. (The default GRIB parameter table
          properly applies only to indexes less than 128.)
        > The index is not present in the applicable parameter table, perhaps because the table is out of date or is otherwise incorrect.
        > The GRIB file has been generated incorrectly, perhaps specifying a wrong parameter table or a non-existent index.

  * Pre-defined grids are enumerated in Table B of the NCEP GRIB1 documentation.
  * GDS Grids types are listed in Table 6 of the NCEP GRIB1 documentation.
  * Level type abbreviations are taken from Table 3 of the NCEP GRIB1 documentation.
  * The abbreviations corresponding to the supported statistical processing methods are:
      ave - average
      acc - accumulation
      dif - difference
  * Note that the duration period and units abbreviation were added in NCL version 4.2.0.a028 in order to handle GRIB files with
    more than one time duration for otherwise identical variables. This is an unavoidable incompatibility for GRIB file variable
    names relative to earlier versions.
 */
  static public String makeVariableName(Grib1Tables tables, GribCollection gribCollection, GribCollection.VariableIndex vindex) {
    Formatter f = new Formatter();

    Grib1Parameter param = tables.getParameter(gribCollection.center, gribCollection.subcenter, vindex.tableVersion, vindex.parameter);

    if (param == null) {
      f.format("VAR%d-%d-%d-%d", gribCollection.center, gribCollection.subcenter, vindex.tableVersion, vindex.parameter);
    } else {
      //if (param.getName() != null)
      //  f.format("%s", param.getName());
      //else
        f.format("%s", Grib1Parameter.makeNameFromDescription(param.getDescription()));
    }

    if (vindex.levelType != GribNumbers.UNDEFINED) { // satellite data doesnt have a level
      f.format("_%s", Grib1ParamLevel.getNameShort(vindex.levelType)); // code table 3
      // if (vindex.isLayer) f.format("_layer"); LOOK ? assumes that cant have two variables on same vertical type, differeing only by isLayer
    }

    if (vindex.intvType >= 0) {
      Grib1ParamTime.StatType stype = Grib1ParamTime.getStatType(vindex.intvType);
      if (stype != null)
        f.format("_%s", stype.name());
    }

    return f.toString();
  }

  static public String makeVariableLongName(Grib1Tables tables, GribCollection gribCollection, GribCollection.VariableIndex vindex) {
    Formatter f = new Formatter();

    boolean isProb = (vindex.probabilityName != null && vindex.probabilityName.length() > 0);
    if (isProb)
      f.format("Probability ");

    Grib1Parameter param = tables.getParameter(gribCollection.center, gribCollection.subcenter, vindex.tableVersion, vindex.parameter);
    if (param == null)
      f.format("Unknown Parameter %d-%d-%d-%d", gribCollection.center, gribCollection.subcenter, vindex.tableVersion, vindex.parameter);
    else
      f.format("%s", param.getDescription());

    if (vindex.intvType >= 0) {
      Grib1ParamTime.StatType stat = Grib1ParamTime.getStatType(vindex.intvType);
      if (stat != null) f.format(" (%s)", stat.name());
    }

    if (vindex.levelType != GribNumbers.UNDEFINED) { // satellite data doesnt have a level
      f.format(" @ %s", Grib1ParamLevel.getNameShort(vindex.levelType));
      if (vindex.isLayer) f.format(" layer");
    }

    return f.toString();
  }

  static public String makeVariableUnits(Grib1Tables tables, GribCollection gribCollection, GribCollection.VariableIndex vindex) {
    Grib1Parameter gp = tables.getParameter(gribCollection.center, gribCollection.subcenter, vindex.tableVersion, vindex.parameter);
    String val = (gp == null) ? "" : gp.getUnit();
    return (val == null) ? "" : val;
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  private TimePartition timePartition;
  private GribCollection gribCollection;
  private Grib1Tables tables;
  private GribCollection.GroupHcs gHcs;
  private boolean isTimePartitioned;
  private boolean owned; // if Iosp is owned by GribCollection; affects close()


  @Override
  public boolean isValidFile(RandomAccessFile raf) throws IOException {
    raf.seek(0);
    byte[] b = new byte[Grib1CollectionBuilder.MAGIC_START.length()];  // LOOK NOT also matches GribCollectionTimePartitioned
    raf.readFully(b);
    String magic = new String(b);
    if (magic.equals(Grib1CollectionBuilder.MAGIC_START)) return true;

    // check for GRIB1 file
    return Grib1RecordScanner.isValidFile(raf);
  }

  @Override
  public String getFileTypeId() {
    return "GRIB1collection";
  }

  @Override
  public String getFileTypeDescription() {
    return "GRIB1 Collection";
  }

  private String lookupTablePath, paramTablePath;
  @Override
  public Object sendIospMessage(Object special) {
    if (special instanceof String) {
      String s = (String) special;
      if (s.startsWith("GribParameterTableLookup")) {
        int pos = s.indexOf("=");
        if (pos > 0)
          lookupTablePath = s.substring(pos+1).trim();

      } else if (s.startsWith("GribParameterTable")) {
        int pos = s.indexOf("=");
        if (pos > 0)
          paramTablePath = s.substring(pos+1).trim();
      }

      System.out.printf("GRIB got IOSP message=%s%n", special);
      return null;
    }
    return super.sendIospMessage(special);
  }


  // public no-arg constructor for reflection
  public Grib1Iosp() {
  }

  public Grib1Iosp(GribCollection.GroupHcs gHcs) {
    this.gHcs = gHcs;
    this.owned = true;
  }

  public Grib1Iosp(GribCollection gc) {
    this.gribCollection = gc;
  }

  @Override
  public void open(RandomAccessFile raf, NetcdfFile ncfile, CancelTask cancelTask) throws IOException {
    super.open(raf, ncfile, cancelTask);
    tables = Grib1Tables.factory(paramTablePath, lookupTablePath);

    boolean isGrib = Grib1RecordScanner.isValidFile(raf);
    if (isGrib) {
      Grib1Index index = new Grib1Index();
      Formatter f= new Formatter();
      this.gribCollection = index.makeCollection(raf, CollectionManager.Force.test, f, 1);
    }

    if (gHcs != null) { // just use the one group that was set in the constructor
      this.gribCollection = gHcs.getGribCollection();
      if (this.gribCollection instanceof TimePartition) {
        isTimePartitioned = true;
        timePartition = (TimePartition) gribCollection;
      }
      addGroup(ncfile, gHcs, false);

    } else if (gribCollection != null) { // use the gribCollection set in the constructor
      if (this.gribCollection instanceof TimePartition) {
        isTimePartitioned = true;
        timePartition = (TimePartition) gribCollection;
      }
      boolean useGroups = gribCollection.getGroups().size() > 1;
      for (GribCollection.GroupHcs g : gribCollection.getGroups())
        addGroup(ncfile, g, useGroups);

    } else { // read in entire collection

      raf.seek(0);
      byte[] b = new byte[TimePartitionBuilder.MAGIC_STARTP.length()];
      raf.readFully(b);
      String magic = new String(b);
      isTimePartitioned = magic.equals(TimePartitionBuilder.MAGIC_STARTP);

      String location = raf.getLocation();
      File f = new File(location);
      int pos = f.getName().lastIndexOf(".");
      String name = (pos > 0) ? f.getName().substring(0, pos) : f.getName();

      // asssume for now this is the grib collection index file (ncx)
      if (isTimePartitioned) {
        timePartition = TimePartitionBuilder.createFromIndex(name, f.getParentFile(), raf);
        gribCollection = timePartition;
      } else {
        gribCollection = Grib1CollectionBuilder.createFromIndex(name, f.getParentFile(), raf);
      }

      boolean useGroups = gribCollection.getGroups().size() > 1;
      for (GribCollection.GroupHcs g : gribCollection.getGroups())
        addGroup(ncfile, g, useGroups);
    }

    String val = CommonCodeTable.getCenterName(gribCollection.getCenter(), 2);
    ncfile.addAttribute(null, new Attribute("Originating/generating Center", val == null ? Integer.toString(gribCollection.getCenter()) : val));
    val = Grib1Utils.getSubCenterName(gribCollection.getCenter(), gribCollection.getSubcenter());
    ncfile.addAttribute(null, new Attribute("Originating/generating Subcenter", val == null ? Integer.toString(gribCollection.getSubcenter()) : val));
    ncfile.addAttribute(null, new Attribute("GRIB table version", gribCollection.getLocal()));
    ncfile.addAttribute(null, new Attribute("GRIB table", gribCollection.getCenter()+"-"+gribCollection.getSubcenter()+"-"+gribCollection.getLocal()));

    val = Grib1Utils.getTypeGenProcessName(gribCollection.getCenter(), gribCollection.getGenProcessId());
    if (val != null)
      ncfile.addAttribute(null, new Attribute("Type of generating process", val));

    ncfile.addAttribute(null, new Attribute("Conventions", "CF-1.6"));
    ncfile.addAttribute(null, new Attribute("history", "Read using CDM IOSP Grib1Collection"));
    ncfile.addAttribute(null, new Attribute("featureType", "GRID"));
    for (Parameter p : gribCollection.getParams())
      ncfile.addAttribute(null, new Attribute(p));
  }

  private void addGroup(NetcdfFile ncfile, GribCollection.GroupHcs gHcs, boolean useGroups) {
    GdsHorizCoordSys hcs = gHcs.hcs;
    VertCoord.assignVertNames(gHcs.vertCoords, tables);
    String grid_mapping = hcs.getName()+"_Projection";
    Group g;
    if (useGroups) {
      g = new Group(ncfile, null, gHcs.getGroupName());
      try {
        ncfile.addGroup(null, g);
      } catch (Exception e) {
        logger.warn("Duplicate Group - skipping");
        return;
      }
    } else {
      g = ncfile.getRootGroup();
    }

    String horizDims;
    if (hcs == null) {
      logger.error("No GdsHorizCoordSys for gds template {} center {}", gHcs.hcs.template, gribCollection.getCenter());
      throw new IllegalStateException();
    }

    if (hcs.isLatLon()) {
      horizDims = "lat lon";
      ncfile.addDimension(g, new Dimension("lon", hcs.nx));
      ncfile.addDimension(g, new Dimension("lat", hcs.ny));

      Variable cv = ncfile.addVariable(g, new Variable(ncfile, g, null, "lat", DataType.FLOAT, "lat"));
      cv.addAttribute(new Attribute(CF.UNITS, "degrees_north"));
      if (hcs.gaussLats != null)
        cv.setCachedData(hcs.gaussLats); //  LOOK do we need to make a copy?
      else
        cv.setCachedData(Array.makeArray(DataType.FLOAT, hcs.ny, hcs.starty, hcs.dy));

      cv = ncfile.addVariable(g, new Variable(ncfile, g, null, "lon", DataType.FLOAT, "lon"));
      cv.addAttribute(new Attribute(CF.UNITS, "degrees_east"));
      cv.setCachedData(Array.makeArray(DataType.FLOAT, hcs.nx, hcs.startx, hcs.dx));

    } else {
      // make horiz coordsys coordinate variable
      Variable hcsV = ncfile.addVariable(g, new Variable(ncfile, g, null, grid_mapping, DataType.INT, ""));
      hcsV.setCachedData(Array.factory(DataType.INT, new int[0], new int[]{0}));
      for (Parameter p : hcs.proj.getProjectionParameters())
        hcsV.addAttribute(new Attribute(p));

      horizDims = "y x";
      ncfile.addDimension(g, new Dimension("x", hcs.nx));
      ncfile.addDimension(g, new Dimension("y", hcs.ny));

      Variable cv = ncfile.addVariable(g, new Variable(ncfile, g, null, "x", DataType.FLOAT, "x"));
      cv.addAttribute(new Attribute(CF.STANDARD_NAME, CF.PROJECTION_X_COORDINATE));
      cv.addAttribute(new Attribute(CF.UNITS, "km"));
      cv.setCachedData(Array.makeArray(DataType.FLOAT, hcs.nx, hcs.startx, hcs.dx));

      cv = ncfile.addVariable(g, new Variable(ncfile, g, null, "y", DataType.FLOAT, "y"));
      cv.addAttribute(new Attribute(CF.STANDARD_NAME, CF.PROJECTION_Y_COORDINATE));
      cv.addAttribute(new Attribute(CF.UNITS, "km"));
      cv.setCachedData(Array.makeArray(DataType.FLOAT, hcs.ny, hcs.starty, hcs.dy));
    }

    // create names, disambiguate vertical coordinates - now in grib collection
    // assignVertNames(gHcs.vertCoords);

    for (VertCoord vc : gHcs.vertCoords) {
      int n = vc.getSize();
      String vcName = vc.getName();
      ncfile.addDimension(g, new Dimension(vcName, n));
      Variable v = ncfile.addVariable(g, new Variable(ncfile, g, null, vcName, DataType.FLOAT, vcName));
      v.addAttribute(new Attribute(CF.UNITS, vc.getUnits()));
      v.addAttribute(new Attribute(CF.LONG_NAME, Grib1ParamLevel.getLevelDescription(vc.getCode())));
      v.addAttribute(new Attribute("positive", vc.isPositiveUp() ? CF.POSITIVE_UP : CF.POSITIVE_DOWN));

      v.addAttribute(new Attribute("GRIB1_level_code", vc.getCode()));
      VertCoord.VertUnit vu = Grib1ParamLevel.getLevelUnit(vc.getCode());
      if (vu != null) {
        if (vu.datum != null)
          v.addAttribute(new Attribute("datum", vu.datum));
      }

      if (vc.isLayer()) {
        float[] data = new float[n];
        int count = 0;
        for (VertCoord.Level val : vc.getCoords())
          data[count++] = (float) (val.getValue1() + val.getValue2()) / 2;
        v.setCachedData(Array.factory(DataType.FLOAT, new int[]{n}, data));

        Variable bounds = ncfile.addVariable(g, new Variable(ncfile, g, null, vcName + "_bounds", DataType.FLOAT, vcName + " 2"));
        v.addAttribute(new Attribute(CF.BOUNDS, vcName + "_bounds"));
        bounds.addAttribute(new Attribute(CF.UNITS, vc.getUnits()));
        bounds.addAttribute(new Attribute(CF.LONG_NAME, "bounds for " + vcName));

        data = new float[2 * n];
        count = 0;
        for (VertCoord.Level level : vc.getCoords()) {
          data[count++] = (float) level.getValue1();
          data[count++] = (float) level.getValue2();
        }
        bounds.setCachedData(Array.factory(DataType.FLOAT, new int[]{n, 2}, data));

      } else {
        float[] data = new float[n];
        int count = 0;
        for (VertCoord.Level val : vc.getCoords())
          data[count++] = (float) val.getValue1();
        v.setCachedData(Array.factory(DataType.FLOAT, new int[]{n}, data));
      }
    }

    for (TimeCoord tc : gHcs.timeCoords) {
      int n = tc.getSize();
      String tcName = tc.getName();
      ncfile.addDimension(g, new Dimension(tcName, n));
      Variable v = ncfile.addVariable(g, new Variable(ncfile, g, null, tcName, DataType.INT, tcName));
      v.addAttribute(new Attribute(CF.UNITS, tc.getUnits()));
      v.addAttribute(new Attribute(CF.STANDARD_NAME, "time"));

      int[] data = new int[n];
      int count = 0;

      if (tc.isInterval()) {
        for (TimeCoord.Tinv tinv : tc.getIntervals()) data[count++] = tinv.getBounds2();
      } else {
        for (int val : tc.getCoords()) data[count++] = val;
      }
      v.setCachedData(Array.factory(DataType.INT, new int[]{n}, data));

      if (tc.isInterval()) {
        Variable bounds = ncfile.addVariable(g, new Variable(ncfile, g, null, tcName + "_bounds", DataType.INT, tcName + " 2"));
        v.addAttribute(new Attribute(CF.BOUNDS, tcName + "_bounds"));
        bounds.addAttribute(new Attribute(CF.UNITS, tc.getUnits()));
        bounds.addAttribute(new Attribute(CF.LONG_NAME, "bounds for " + tcName));

        data = new int[2 * n];
        count = 0;
        for (TimeCoord.Tinv tinv : tc.getIntervals()) {
          data[count++] = tinv.getBounds1();
          data[count++] = tinv.getBounds2();
        }
        bounds.setCachedData(Array.factory(DataType.INT, new int[]{n, 2}, data));
      }
    }

    int ccount = 0;
    for (EnsCoord ec : gHcs.ensCoords) {
      int n = ec.getSize();
      String ecName = "ens" + ccount;
      ncfile.addDimension(g, new Dimension(ecName, n));
      Variable v = new Variable(ncfile, g, null, ecName, DataType.INT, ecName);
      ncfile.addVariable(g, v);
      ccount++;
      v.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Ensemble.toString()));

      int[] data = new int[n];
      int count = 0;
      for (EnsCoord.Coord ecc : ec.getCoords())
        data[count++] = ecc.getEnsMember();
      v.setCachedData(Array.factory(DataType.INT, new int[]{n}, data));
    }

    for (GribCollection.VariableIndex vindex : gHcs.varIndex) {
      TimeCoord tc = gHcs.timeCoords.get(vindex.timeIdx);
      VertCoord vc = (vindex.vertIdx < 0) ? null : gHcs.vertCoords.get(vindex.vertIdx);
      EnsCoord ec = (vindex.ensIdx < 0) ? null : gHcs.ensCoords.get(vindex.ensIdx);

      StringBuilder dims = new StringBuilder();

      // canonical order: time, ens, z, y, x
      String tcName = tc.getName();
      dims.append(tcName);

      if (ec != null)
        dims.append(" ").append("ens").append(vindex.ensIdx);

      if (vc != null)
        dims.append(" ").append(vc.getName());

      dims.append(" ").append(horizDims);

      String vname = makeVariableName(tables, gribCollection, vindex);
      Variable v = new Variable(ncfile, g, null, vname, DataType.FLOAT, dims.toString());
      ncfile.addVariable(g, v);
      //System.out.printf("added %s%n",vname);

      String desc = makeVariableLongName(tables, gribCollection, vindex);
      v.addAttribute(new Attribute(CF.LONG_NAME, desc));
      v.addAttribute(new Attribute(CF.UNITS, makeVariableUnits(tables, gribCollection, vindex)));
      v.addAttribute(new Attribute(CF.MISSING_VALUE, MISSING_VALUE));
      v.addAttribute(new Attribute(CF.GRID_MAPPING, grid_mapping));

      v.addAttribute(new Attribute("Grib_Parameter", vindex.parameter));
      v.addAttribute(new Attribute("Grib_Level_Type", vindex.levelType));
      if (vindex.intvType >= 0) {
        v.addAttribute(new Attribute("Grib_Statistical_Interval_Type", vindex.intvType));
        CF.CellMethods cm = CF.CellMethods.convertGrib1code(vindex.intvType);
        if (cm != null)
          v.addAttribute(new Attribute("cell_methods", tcName + ": " + cm.toString()));
      }
      if (vindex.ensDerivedType >= 0)
        v.addAttribute(new Attribute("Grib_Ensemble_Derived_Type", vindex.ensDerivedType));
      else if (vindex.probabilityName != null && vindex.probabilityName.length() > 0)
        v.addAttribute(new Attribute("Grib_Probability_Type", vindex.probabilityName));

      v.setSPobject(vindex);
    }
  }

  @Override
  public void close() throws java.io.IOException {
    //if (!owned && gribCollection != null) // klugerino
    if (gribCollection != null)
      gribCollection.close();
  }

  @Override
  public String getDetailInfo() {
    Formatter f = new Formatter();
    if (gribCollection != null)
      gribCollection.showIndex(f);
    return f.toString();
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////

  @Override
  public Array readData(Variable v2, Section section) throws IOException, InvalidRangeException {
    long start = System.currentTimeMillis();

    Array result;
    if (isTimePartitioned)
      result = readDataFromPartition(v2, section);
    else
      result = readDataFromCollection(v2, section);

    long took = System.currentTimeMillis() - start;
    if (debugTime) System.out.println("  read data took=" + took + " msec ");
    return result;
  }

  /* private Array readDataFromPartition(Variable v2, Section section) throws IOException, InvalidRangeException {
    TimePartition.VariableIndexPartitioned vindexP = (TimePartition.VariableIndexPartitioned) v2.getSPobject();

    // canonical order: time, ens, z, y, x
    int rangeIdx = 0;
    Range timeRange = (section.getRank() > 2) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range ensRange = (vindexP.ensIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range levRange = (vindexP.vertIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range yRange = section.getRange(rangeIdx++);
    Range xRange = section.getRange(rangeIdx);

    DataReceiver dataReceiver = new DataReceiver(section, yRange, xRange);
    DataReaderPartitioned dataReader = new DataReaderPartitioned();

    TimePartition.TimeCoordPartitioned timeCoord = vindexP.getTimeCoord();
    int firstPartition = timeCoord.findPartition(timeRange.first());

    List<TimePartition.Partition> partitions = timePartition.getPartitions();
    for (int partno = firstPartition; partno < partitions.size(); partno++) {

      Range localRange = timeCoord.getLocalRange(partno, timeRange);
      if (localRange == null) {
        if (timeCoord.after(partno, timeRange.last())) break; // termination condition
        continue; // no intersection
      }
      //if (debug)
      //  System.out.println("   agg use " + nested.aggStart + ":" + nested.aggEnd + " range= " + nestedJoinRange + " file " + nested.getLocation());

      // at this point, we need to instantiate the Partition and the vindex.records
      GribCollection.VariableIndex vindex = vindexP.getVindex(partno);

      // collect all the records from this partition that need to be read
      for (int timeIdx = localRange.first(); timeIdx <= localRange.last(); timeIdx += localRange.stride()) {
        for (int ensIdx = ensRange.first(); ensIdx <= ensRange.last(); ensIdx += ensRange.stride()) {
          for (int levelIdx = levRange.first(); levelIdx <= levRange.last(); levelIdx += levRange.stride()) {

            // where this particular record fits into the result array, modulo horiz
            int globalTimeIndex = timeRange.index(timeCoord.startingIndexGlobal(partno) + timeIdx);

            //   public static int calcIndex(int timeIdx, int vertIdx, int ensIdx, int nens, int nverts) {
            int resultIndex = GribCollection.calcIndex(globalTimeIndex, ensRange.index(ensIdx), levRange.index(levelIdx),
                    ensRange.length(), levRange.length());

            dataReader.addRecord(partno, vindex, timeIdx, ensIdx, levelIdx, resultIndex);
          }
        }
      }
    }

    // sort by file and position, then read
    dataReader.read(dataReceiver);
    return dataReceiver.getArray();
  } */

  private Array readDataFromPartition(Variable v2, Section section) throws IOException, InvalidRangeException {
    TimePartition.VariableIndexPartitioned vindexP = (TimePartition.VariableIndexPartitioned) v2.getSPobject();

    // canonical order: time, ens, z, y, x
    int rangeIdx = 0;
    Range timeRange = (section.getRank() > 2) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range ensRange = (vindexP.ensIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range levRange = (vindexP.vertIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range yRange = section.getRange(rangeIdx++);
    Range xRange = section.getRange(rangeIdx);

    DataReceiver dataReceiver = new DataReceiver(section, yRange, xRange);
    DataReaderPartitioned dataReader = new DataReaderPartitioned();

    TimeCoordUnion timeCoordP = (TimeCoordUnion) vindexP.getTimeCoord();

    // collect all the records from this partition that need to be read
    for (int timeIdx = timeRange.first(); timeIdx <= timeRange.last(); timeIdx += timeRange.stride()) {

      TimeCoordUnion.Val val = timeCoordP.getVal(timeIdx);
      GribCollection.VariableIndex vindex = vindexP.getVindex(val.getPartition());

      for (int ensIdx = ensRange.first(); ensIdx <= ensRange.last(); ensIdx += ensRange.stride()) {
        for (int levelIdx = levRange.first(); levelIdx <= levRange.last(); levelIdx += levRange.stride()) {

          // where does this record go in the result ??
          int resultIndex = GribCollection.calcIndex(timeRange.index(timeIdx), ensRange.index(ensIdx), levRange.index(levelIdx),
                  ensRange.length(), levRange.length());

          // get the record from the partition
          int recordIndex = GribCollection.calcIndex(val.getIndex(), ensIdx, levelIdx, vindex.nens, vindex.nverts);
          // System.out.printf(" GribCollection.Record == %d (%d, %d, %d) %n", recordIndex, timeIdx, ensIdx, levIdx);
          GribCollection.Record record = vindex.records[recordIndex];

          // add this record to be read
          dataReader.addRecord(vindex, val.getPartition(), record.fileno, record.pos, resultIndex);
        }
      }
    }

    // sort by file and position, then read
    dataReader.read(dataReceiver);
    return dataReceiver.getArray();
  }

  /* private Array readDataFromPartition2(Variable v, Section section) throws IOException, InvalidRangeException {
    TimePartition.VariableIndexPartitioned vindexP = (TimePartition.VariableIndexPartitioned) v.getSPobject();

    // first time, read records and keep in memory
    if (vindex.records == null)
      vindex.readRecords();

    // canonical order: time, ens, z, y, x
    int rangeIdx = 0;
    Range timeRange = (section.getRank() > 2) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range ensRange = (vindexP.ensIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range levRange = (vindexP.vertIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range yRange = section.getRange(rangeIdx++);
    Range xRange = section.getRange(rangeIdx);

    DataReceiver dataReceiver = new DataReceiver(section, yRange, xRange);
    DataReaderPartitioned dataReader = new DataReaderPartitioned();

    // collect all the records that need to be read
    for (int timeIdx = timeRange.first(); timeIdx <= timeRange.last(); timeIdx += timeRange.stride()) {
      for (int ensIdx = ensRange.first(); ensIdx <= ensRange.last(); ensIdx += ensRange.stride()) {
        for (int levelIdx = levRange.first(); levelIdx <= levRange.last(); levelIdx += levRange.stride()) {
          // where this particular record fits into the result array, modulo horiz
          int resultIndex = GribCollection.calcIndex(timeRange.index(timeIdx), ensRange.index(ensIdx),levRange.index(levelIdx),
                  ensRange.length(), levRange.length());
          dataReader.addRecord(ensIdx, timeIdx, levelIdx, resultIndex);
        }
      }
    }

    // sort by file and position, then read
    dataReader.read(dataReceiver);
    return dataReceiver.getArray();
  }  */


  private class DataReaderPartitioned {
    List<DataRecord> records = new ArrayList<DataRecord>();

    private DataReaderPartitioned() {
    }

    void addRecord(GribCollection.VariableIndex vindex, int partno, int fileno, long pos, int resultIndex) {
      records.add(new DataRecord(partno, vindex, resultIndex, fileno, pos));
    }

    void read(DataReceiver dataReceiver) throws IOException {
      Collections.sort(records);

      int currPartno = -1;
      int currFile = -1;
      RandomAccessFile rafData = null;
      for (DataRecord dr : records) {
        if (dr.partno != currPartno || dr.fileno != currFile) {
          if (rafData != null) rafData.close();
          rafData = timePartition.getRaf(dr.partno, dr.fileno);
          currFile = dr.fileno;
          currPartno = dr.partno;
        }

        if (dr.pos == GribCollection.MISSING_RECORD) continue; // skip missing data

        if (debugRead) { // for validation
          rafData.seek(dr.pos);
          Grib1Record gr = new Grib1Record(rafData);
          if (gr != null) {
            Grib1SectionProductDefinition pds = gr.getPDSsection();
            Grib1Parameter param = tables.getParameter(pds.getCenter(), pds.getSubCenter(), pds.getTableVersion(), pds.getParameterNumber());
            Formatter f = new Formatter();
            f.format("File=%s%n", rafData.getLocation());
            f.format("  Parameter=%s%n", param);
            f.format("  ReferenceDate=%s%n", gr.getReferenceDate());
            Grib1ParamTime ptime = pds.getParamTime();
            f.format("  ForecastTime=%d%n", ptime.getForecastTime());
            if (ptime.isInterval()) {
              int tinv[] = ptime.getInterval();
              f.format("  TimeInterval=(%d,%d)%n", tinv[0], tinv[1]);
            }
            f.format("%n");
            gr.getPDSsection().showPds(tables, f);
            System.out.printf(" Grib1Record.readData at drsPos %d = %s%n", dr.pos, f.toString());
          }
        }

        float[] data = null; // LOOK Grib1Record.readData(rafData, dr.drsPos, dr.vindex.group.hcs.nPoints, dr.vindex.group.hcs.scanMode, dr.vindex.group.hcs.nx);
        dataReceiver.addData(data, dr.resultIndex, dr.vindex.group.hcs.nx);
      }
      if (rafData != null) rafData.close();
    }

    private class DataRecord implements Comparable<DataRecord> {
      int partno; // partition index
      GribCollection.VariableIndex vindex; // the vindex of the partition
      int resultIndex; // where does this record go in the result array?
      int fileno;
      long pos;

      DataRecord(int partno, GribCollection.VariableIndex vindex, int resultIndex, int fileno, long pos) {
        this.partno = partno;
        this.vindex = vindex;
        this.resultIndex = resultIndex;
        this.fileno = fileno;
        this.pos = pos;
      }

      @Override
      public int compareTo(DataRecord o) {
        int r = partno - o.partno;
        if (r != 0) return r;
        r = fileno - o.fileno;
        return (r != 0) ? r : (int) (pos - o.pos);
      }
    }
  }

///////////////////////////////////////////////////////

  private Array readDataFromCollection(Variable v, Section section) throws IOException, InvalidRangeException {
    GribCollection.VariableIndex vindex = (GribCollection.VariableIndex) v.getSPobject();

    // first time, read records and keep in memory
    if (vindex.records == null)
      vindex.readRecords();

    // canonical order: time, ens, z, y, x
    int rangeIdx = 0;
    Range timeRange = (section.getRank() > 2) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range ensRange = (vindex.ensIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range levRange = (vindex.vertIdx >= 0) ? section.getRange(rangeIdx++) : new Range(0, 0);
    Range yRange = section.getRange(rangeIdx++);
    Range xRange = section.getRange(rangeIdx);

    DataReceiver dataReceiver = new DataReceiver(section, yRange, xRange);
    DataReader dataReader = new DataReader(vindex);

    // collect all the records that need to be read
    for (int timeIdx = timeRange.first(); timeIdx <= timeRange.last(); timeIdx += timeRange.stride()) {
      for (int ensIdx = ensRange.first(); ensIdx <= ensRange.last(); ensIdx += ensRange.stride()) {
        for (int levelIdx = levRange.first(); levelIdx <= levRange.last(); levelIdx += levRange.stride()) {
          // where this particular record fits into the result array, modulo horiz
          int resultIndex = GribCollection.calcIndex(timeRange.index(timeIdx), ensRange.index(ensIdx), levRange.index(levelIdx),
                  ensRange.length(), levRange.length());
          dataReader.addRecord(ensIdx, timeIdx, levelIdx, resultIndex);
        }
      }
    }

    // sort by file and position, then read
    dataReader.read(dataReceiver);
    return dataReceiver.getArray();
  }

  private class DataReader {
    GribCollection.VariableIndex vindex;
    List<DataRecord> records = new ArrayList<DataRecord>();

    private DataReader(GribCollection.VariableIndex vindex) {
      this.vindex = vindex;
    }

    void addRecord(int ensIdx, int timeIdx, int levIdx, int resultIndex) {
      int recordIndex = GribCollection.calcIndex(timeIdx, ensIdx, levIdx, vindex.nens, vindex.nverts);
      GribCollection.Record record = vindex.records[recordIndex];
      records.add(new DataRecord(timeIdx, ensIdx, levIdx, resultIndex, record.fileno, record.pos));
    }

    void read(DataReceiver dataReceiver) throws IOException {
      Collections.sort(records);

      int currFile = -1;
      RandomAccessFile rafData = null;
      for (DataRecord dr : records) {
        if (dr.fileno != currFile) {
          if (rafData != null) rafData.close();
          rafData = gribCollection.getRaf(dr.fileno);
          currFile = dr.fileno;
        }

        if (dr.pos == GribCollection.MISSING_RECORD) continue;

        if (debugRead) { // for validation
          rafData.seek(dr.pos);
          Grib1Record gr = new Grib1Record(rafData);
          if (gr != null) {
            Formatter f = new Formatter();
            f.format("File=%s%n", raf.getLocation());
            Grib1SectionProductDefinition pds = gr.getPDSsection();
            Grib1Parameter param = tables.getParameter(pds.getCenter(), pds.getSubCenter(), pds.getTableVersion(), pds.getParameterNumber());
            f.format("  Parameter=%s%n", param);
            f.format("  ReferenceDate=%s%n", gr.getReferenceDate());
            Grib1ParamTime ptime = pds.getParamTime();
            f.format("  ForecastTime=%d%n", ptime.getForecastTime());
            if (ptime.isInterval()) {
              int tinv[] = ptime.getInterval();
              f.format("  TimeInterval=(%d,%d)%n", tinv[0], tinv[1]);
            }
            f.format("%n");
            gr.getPDSsection().showPds(tables, f);
            System.out.printf("%nGrib1Record.readData at drsPos %d = %s%n", dr.pos, f.toString());
          }
        }

        float[] data = Grib1Record.readData(rafData, dr.pos);
        dataReceiver.addData(data, dr.resultIndex, vindex.group.hcs.nx);
      }
      if (rafData != null) rafData.close();
    }

    private class DataRecord implements Comparable<DataRecord> {
      int ensIdx, timeIdx, levIdx;
      int resultIndex; // index in the ens / time / vert array
      int fileno;
      long pos;

      DataRecord(int timeIdx, int ensIdx, int levIdx, int resultIndex, int fileno, long pos) {
        this.ensIdx = ensIdx;
        this.timeIdx = timeIdx;
        this.levIdx = levIdx;
        this.resultIndex = resultIndex;
        this.fileno = fileno;
        this.pos = pos;
      }

      @Override
      public int compareTo(DataRecord o) {
        int r = fileno - o.fileno;
        return (r == 0) ? (int) (pos - o.pos) : r;
      }
    }
  }

  private class DataReceiver {
    Array dataArray;
    Range yRange, xRange;
    int horizSize;

    DataReceiver(Section section, Range yRange, Range xRange) {
      dataArray = Array.factory(DataType.FLOAT, section.getShape());
      this.yRange = yRange;
      this.xRange = xRange;
      this.horizSize = yRange.length() * xRange.length();

      // prefill with NaNs, to deal with missing data
      IndexIterator iter = dataArray.getIndexIterator();
      while (iter.hasNext())
        iter.setFloatNext(MISSING_VALUE);
    }

    void addData(float[] data, int resultIndex, int nx) throws IOException {
      int start = resultIndex * horizSize;
      int count = 0;
      for (int y = yRange.first(); y <= yRange.last(); y += yRange.stride()) {
        for (int x = xRange.first(); x <= xRange.last(); x += xRange.stride()) {
          int dataIdx = y * nx + x;
          dataArray.setFloat(start + count, data[dataIdx]);
          count++;
        }
      }
    }

    Array getArray() {
      return dataArray;
    }
  }
  }
