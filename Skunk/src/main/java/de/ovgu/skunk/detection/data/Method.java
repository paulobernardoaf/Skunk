package de.ovgu.skunk.detection.data;

import de.ovgu.skunk.util.FileUtils;

import java.util.*;

/**
 * Representation of a function in the analyzed source code
 */
public class Method {
    /**
     * Compares functions by occurrence in their file, in ascending order.  Specifically, functions are first compared
     * by the name of the file, then by line number.  Although unneccessary, they are also compared by function
     * signature as a last resort.
     */
    public static final Comparator<? super Method> COMP_BY_OCCURRENCE = new Comparator<Method>() {
        @Override
        public int compare(Method f1, Method f2) {
            int cmp;
            cmp = f1.filePath.compareTo(f2.filePath);
            if (cmp != 0) return cmp;
            cmp = f1.start1 - f2.start1;
            if (cmp != 0) return cmp;
            return f1.functionSignatureXml.compareTo(f2.functionSignatureXml);
        }
    };

    private final Context ctx;
//    /**
//     * Source code of the functions, as returned from the srcml function node
//     */
//    private final String sourceCode;
    /**
     * The function signature.
     */
    public String functionSignatureXml;
    /**
     * The first line of the function in the file, counted from 1.
     */
    public int start1;
    /**
     * The last line of the function in the file, counted from 1.
     */
    public int end1;
    /**
     * The lines of code of the method, including empty lines.
     */
    private int grossLoc;

    /**
     * The lines of code of the function, excluding empty lines.
     */
    private int netLoc = -1;

    /**
     * The lines of feature code inside the method.
     */
    public long lofc;
    /**
     * The amount of nestings in the method (1 per nesting)
     */
    public int nestingSum;
    /**
     * The maximal nesting depth in the method
     */
    public int nestingDepthMax;
    /**
     * The lines of visible annotated code. (amount of loc that is inside
     * annotations)
     */
    public List<Integer> loac;
    private int processedLoac;
    /**
     * The map of the feature constants, by order of appearance
     */
    public Map<UUID, String> featureReferences;
    /**
     * The number feature constants in the method (non-duplicated).
     */
    public int numberFeatureConstantsNonDup;
    /**
     * The number feature locations.
     */
    public int numberFeatureLocations;
    /**
     * The number of negations in the method
     */
    public int negationCount;
    /**
     * The file path.
     */
    public String filePath;

    /**
     * Method.
     *
     * @param signature the signature
     * @param start1    the starting line of the function within it's file (first line in the file is counted as 1)
     * @param grossLoc  length of the function in lines of code, may include empty lines
     */
    public Method(Context ctx, String signature, String filePath, int start1, int grossLoc
                  //, String sourceCode
    ) {
        this.ctx = ctx;
        this.functionSignatureXml = signature;
        this.start1 = start1;
        this.grossLoc = grossLoc;
        this.nestingSum = 0;
        this.nestingDepthMax = 0;
        // do not count start1 line while calculating the end1
        this.end1 = start1 + grossLoc - 1;
        // initialize loc
        this.lofc = 0;
        this.featureReferences = new LinkedHashMap<>();
        this.loac = new ArrayList<>();
        this.numberFeatureConstantsNonDup = 0;
        this.numberFeatureLocations = 0;
        this.negationCount = 0;
        this.filePath = filePath;
        //this.sourceCode = sourceCode;
    }

    /**
     * Adds the feature location if it is not already added.
     *
     * @param featureRef the loc
     */
    public void AddFeatureConstant(FeatureReference featureRef) {
        if (!this.featureReferences.containsKey(featureRef.id)) {
            // connect feature to the method
            this.featureReferences.put(featureRef.id, featureRef.feature.Name);
            featureRef.inMethod = this;
            // assign nesting depth values
            if (featureRef.nestingDepth > this.nestingDepthMax) this.nestingDepthMax = featureRef.nestingDepth;
            // calculate lines of feature code (if the feature is longer than
            // the method, use the method end1)
            final int lofcEnd = Math.min(featureRef.end, this.end1);
            if (featureRef.start > lofcEnd) {
                throw new RuntimeException("Internal error: attempt to assign feature reference that starts behind the function's end. function=" + this + "; featureRef=" + featureRef);
            }
            final int lofcStart = featureRef.start;
            if (lofcStart < this.start1) {
                throw new RuntimeException("Internal error: attempt to calculate LOCF for reference that starts before the function's start (LOFC count will be off). function=" + this + "; featureRef=" + featureRef);
            }
            if (lofcStart > lofcEnd) {
                throw new RuntimeException("Internal error: attempt to calculate LOCF where start > end. function=" + this + "; featureRef=" + featureRef + "; lofcStart=" + lofcStart + "; lofcEnd=" + lofcEnd);
            }

            final int lofcIncrement = lofcEnd - lofcStart + 1;
            this.lofc += lofcIncrement;
            File file = ctx.files.FindFile(featureRef.filePath);
            File file1 = ctx.files.FindFile(this.filePath);
            if (file != file1) {
                throw new RuntimeException("Looking at two different files (should be identical): " + file + ", " + file1);
            }

            for (int current : file.emptyLines) {
                if (featureRef.end > this.end1) {
                    if (current > featureRef.start && current < this.end1) this.lofc--;
                } else if (current > featureRef.start && current < featureRef.end) this.lofc--;
            }
            // add lines of visible annotated code (amount of loc that is
            // inside annotations) until end of feature constant or end of
            // method
            for (int current = lofcStart; current <= lofcEnd; current++) {
                if (!(this.loac.contains(current)) && !file.emptyLines.contains(current))
                    this.loac.add(current);
            }
        }
    }

    /**
     * Gets amount of feature constants (duplicated)
     *
     * @return the int
     */
    public int GetFeatureConstantCount() {
        return this.featureReferences.size();
    }

    /**
     * Gets the lines of annotated code.
     *
     * @return lines of visible annotated code (not counting doubles per feature,..)
     */
    public int GetLinesOfAnnotatedCode() {
        return this.processedLoac;
    }

    /**
     * Gets the number of feature constants of the method (non-duplicated)
     *
     * @return the int
     */
    public void SetNumberOfFeatureConstantsNonDup() {
        ArrayList<String> constants = new ArrayList<>();
        for (UUID id : featureReferences.keySet()) {
            FeatureReference constant = ctx.featureExpressions.GetFeatureConstant(featureReferences.get(id), id);
            if (!constants.contains(constant.feature.Name)) constants.add(constant.feature.Name);
        }
        this.numberFeatureConstantsNonDup = constants.size();
    }

    /**
     * Gets the number of feature locations. A feature location is a complete
     * set of feature constants on one line.
     *
     * @return the number of feature occurences in the method
     */
    public void SetNumberOfFeatureLocations() {
        ArrayList<Integer> noLocs = new ArrayList<>();
        // remember the starting position of each feature location, but do not
        // add it twice
        for (UUID id : featureReferences.keySet()) {
            FeatureReference constant = ctx.featureExpressions.GetFeatureConstant(featureReferences.get(id), id);
            if (!noLocs.contains(constant.start)) noLocs.add(constant.start);
        }
        this.processedLoac = this.loac.size();
        this.numberFeatureLocations = noLocs.size();
    }

    /**
     * Cet the amount of negated annotations
     *
     * @return the amount of negated annotations
     */
    public void SetNegationCount() {
        int result = 0;
        for (UUID id : featureReferences.keySet()) {
            FeatureReference constant = ctx.featureExpressions.GetFeatureConstant(featureReferences.get(id), id);
            if (constant.notFlag) result++;
        }
        this.negationCount = result;
    }

    /**
     * Sets the nesting sum.
     */
    public void SetNestingSum() {
        // minNesting defines the lowest nesting depth of the method (nesting
        // depths are file based)
        int res = 0;
        int minNesting = 5000;
        // add each nesting to the nesting sum
        for (UUID id : featureReferences.keySet()) {
            FeatureReference constant = ctx.featureExpressions.GetFeatureConstant(featureReferences.get(id), id);
            res += constant.nestingDepth;
            if (constant.nestingDepth < minNesting) minNesting = constant.nestingDepth;
        }
        // substract the complete minNesting depth (for each added location)
        res -= this.featureReferences.size() * minNesting;
        this.nestingSum = res;
    }

    public void InitializeNetLocMetric() {
        de.ovgu.skunk.detection.data.File file = ctx.files.FindFile(this.filePath);
        this.netLoc = this.grossLoc;
        for (int empty : file.emptyLines) {
            if (empty >= this.start1 && empty <= this.end1) this.netLoc--;
        }
    }

    public int getNetLoc() {
        int r = this.netLoc;
        if (r < 0) {
            throw new AssertionError("Attempt to read net LOC before initializing it.");
        }
        return this.netLoc;
    }

    public int getGrossLoc() {
        return this.grossLoc;
    }

    public String FilePathForDisplay() {
        return FileUtils.displayPathFromCppstatsSrcMlPath(filePath);
    }

    /**
     * @return The original source file's path, relative to the project's repository root.
     */
    public String ProjectRelativeFilePath() {
        return FileUtils.projectRelativePathFromCppstatsSrcMlPath(filePath);
    }

    @Override
    public String toString() {
        return String.format("Function [%s /* %s:%d,%d */]", functionSignatureXml, FilePathForDisplay(),
                start1, end1);
    }

//    public String sourceCodeWithLineNumbers() {
//        String[] lines = sourceCode.split("\n");
//        StringBuilder r = new StringBuilder();
//        int lineNo = start1;
//        for (int iLine = 0; iLine < lines.length; iLine++, lineNo++) {
//            r.append(String.format("% 5d: ", lineNo));
//            r.append(lines[iLine]);
//            r.append("\n");
//        }
//        return r.toString();
//    }

    /*
     * NOTE, 2016-11-18, wf: Generated by Eclipse
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((filePath == null) ? 0 : filePath.hashCode());
        result = prime * result + ((functionSignatureXml == null) ? 0 : functionSignatureXml.hashCode());
        return result;
    }

    /*
     * NOTE, 2016-11-18, wf: Generated by Eclipse
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (!(obj instanceof Method)) return false;
        Method other = (Method) obj;
        if (filePath == null) {
            if (other.filePath != null) return false;
        } else if (!filePath.equals(other.filePath)) return false;
        if (functionSignatureXml == null) {
            if (other.functionSignatureXml != null) return false;
        } else if (!functionSignatureXml.equals(other.functionSignatureXml)) return false;
        return true;
    }

//    /**
//     * @return Source code of the function as parsed by src2srcml
//     */
//    public String getSourceCode() {
//        return sourceCode;
//    }
}
