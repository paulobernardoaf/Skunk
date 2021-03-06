package com.easy.detection.input;

import com.easy.detection.data.FilePath;
import com.easy.detection.data.Method;
import org.apache.log4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringWriter;
import java.util.*;

/**
 * Extracts a normalized function signature from a SrcML node representing a C function definition
 * <p>
 * Created by wfenske on 07.03.17.
 */
public class FunctionSignatureParser {
    private static Logger LOG = Logger.getLogger(FunctionSignatureParser.class);

    private static final ThreadLocal<XPath> tlXPath = ThreadLocal.withInitial(() -> XPathFactory.newInstance().newXPath());

    private final String functionNodeTextContent;
    private final Node functionNode;
    private final FilePath filePath;
    StringBuilder result;
    boolean debugParseExceptions = false;

    public FunctionSignatureParser(Node functionNode, FilePath filePath) {
        this.functionNode = functionNode;
        this.functionNodeTextContent = functionNode.getTextContent();
        this.filePath = filePath;
        if (LOG.isDebugEnabled()) {
            enableDebugParseExceptions();
        }
    }

    /**
     * Turns on log messages and more elaborate error reporting, but will also slow down the parser.
     */
    public void enableDebugParseExceptions() {
        this.debugParseExceptions = true;
    }

    /**
     * Parses K&R-style function definitions, such as
     * <p>
     * static int
     * newerf (f1, f2)
     * char *f1, *f2;
     * { ... }
     * <p>
     * which will be parsed into SrcML like this:
     * <p>
     * <pre>
     * <function>
     *      <type>
     *          <specifier>static</specifier>
     *          <name>int</name>
     *      </type>
     *      <name>newerf</name>
     *      <parameter_list>(
     *          <param><decl><type><name>f1</name></type></decl></param>,
     *          <param><decl><type><name>f2</name></type></decl></param>
     *      )</parameter_list>
     *      <decl_stmt>
     *          <decl><type><name>char</name> *</type><name>f1</name></decl>,
     *          <decl><type ref="prev"/>*<name>f2</name></decl>;
     *      </decl_stmt>
     * </function>
     * </pre>
     *
     * @return Normalized function signature representation
     */
//        private String parseKandRFunctionSignature() throws FunctionSignatureParseException {
//            result = new StringBuilder();
//
//            parseUpToIncludingFunctionName();
//
//            // Handle parameter list
//            result.append('(');
//            parseKandRFunctionParamList();
//            result.append(')');
//            // End parameter list
//
//            return postProcessSignature(result.toString());
//        }

    /**
     * <pre>
     * <function>
     *     <type>
     *         <specifier>static</specifier>
     *         <specifier>const</specifier>
     *         <name>char</name> *
     *     </type>
     *     <name>add_setenvif</name>
     *     <parameter_list>(
     *          <param>
     *              <decl>
     *                  <type><name>cmd_parms</name> *</type>
     *                  <name>cmd</name>
     *              </decl>
     *          </param>,
     *          <param>
     *              <decl>
     *                  <type>
     *                      <name>void</name> *
     *                  </type>
     *                  <name>mconfig</name>
     *              </decl>
     *          </param>,
     *          <param>
     *              <decl>
     *                  <type>
     *                      <specifier>const</specifier>
     *                      <name>char</name> *
     *                  </type>
     *                  <name>args</name>
     *              </decl>
     *          </param>)
     *     </parameter_list>
     * <block>{ ... }</block>
     * </function>
     * </pre>
     *
     * @return
     * @throws FunctionSignatureParseException
     */
    private ParsedFunctionSignature parseRegularFunctionSignature() throws FunctionSignatureParseException {
        result = new StringBuilder();
        parseUpToIncludingFunctionName();

        // Handle parameter list
        result.append('(');
        Node lastNode = parseRegularFunctionParamList();
        result.append(')');
        // End parameter list

        int startOfSignature = get1BasedNodeLineNumber(functionNode);
        int endOfSignature = get1BasedNodeLineNumber(lastNode);
        int loc = endOfSignature + 1 - startOfSignature;
        final String signature = result.toString();
        if (loc < 1) {
            LOG.info("Computed signature LOC " + loc + " for function " + signature + ". Adjusting to 1.");
            loc = 1;
        }

        return postProcessSignature(signature, startOfSignature, loc);
    }

    private void parseUpToIncludingFunctionName() throws FunctionSignatureParseException {
        Node returnType = getNodeOrDie(functionNode, "./type");
        List<Node> returnTypeSpecifiers = getPossiblyEmptyNodeList(returnType, "./specifier");
        for (Node returnTypeSpecifier : returnTypeSpecifiers) {
            String content = returnTypeSpecifier.getTextContent();
            if (result.length() > 0) result.append(' ');
            result.append(content);
        }
        Node returnTypeName = getNodeOrDie(returnType, "./name");
        String returnTypeNameString = returnTypeName.getTextContent();
        if (result.length() > 0) result.append(' ');
        result.append(returnTypeNameString);
        Node functionName = getNodeOrDie(functionNode, "./name");
        String functionNameString = functionName.getTextContent();
        if (result.length() > 0) result.append(' ');
        result.append(functionNameString);
    }

//        private void parseKandRFunctionParamList() throws FunctionSignatureParseException {
//            Node parameterList = getNodeOrDie(functionNode, "./parameter_list");
//            List<Node> params = getPossiblyEmptyNodeList(parameterList, "./param");
//            boolean firstParam = true;
//            for (Node param : params) {
//                if (firstParam) {
//                    firstParam = false;
//                } else {
//                    result.append(',').append(' ');
//                }
//
//                Node paramName = getNodeOrDie(param, "./decl/type/name");
//                String paramNameString = paramName.getTextContent();
//                result.append(paramNameString);
//            }
//        }

    private Node parseRegularFunctionParamList() throws FunctionSignatureParseException {
        Node parameterList = getNodeOrDie(functionNode, "./parameter_list");
        Node lastNode = parameterList;
        List<Node> params = getPossiblyEmptyNodeList(parameterList, "./param");
        Iterator<Node> iParam = params.iterator();
        if (iParam.hasNext()) {
            // parse first parameter
            lastNode = parseParam(iParam.next());
            // parse rest of parameters, if any
            while (iParam.hasNext()) {
                result.append(',').append(' ');
                lastNode = parseParam(iParam.next());
            }
        }
        return lastNode;
    }

    private Node parseParam(Node param) throws FunctionSignatureParseException {
        Node typeDeclarationNode = getNodeOrDie(param, "./decl/type");
        Node lastNode = typeDeclarationNode;
        Optional<Node> optParamTypeName = getOptionalNode(typeDeclarationNode, "./name");
        if (optParamTypeName.isPresent()) {
            Node paramTypeName = optParamTypeName.get();
            lastNode = paramTypeName;
            String paramTypeNameString = paramTypeName.getTextContent();
            // NOTE, 2017-03-06, wf: By making the parameter name optional, we effectively also allow K&R style function definitions
            Optional<Node> optParamName = getOptionalNode(param, "./decl/name");
            if (optParamName.isPresent()) {
                Node paramName = optParamName.get();
                lastNode = paramName;
                String paramNameString = paramName.getTextContent();
                result.append(paramTypeNameString).append(' ').append(paramNameString);
            } else {
                result.append(paramTypeNameString);
            }
        } else {
            // May happen for function signature that include ellipses, e.g., printf(char *format, ...)
            String typeDeclarationText = typeDeclarationNode.getTextContent();
            result.append(typeDeclarationText);
        }

        return lastNode;
    }

    private ParsedFunctionSignature parseFunctionSignatureQuickAndDirty() {
        // get the text content of the node (signature + method content),
        // and remove method content until beginning of block
        //deleteComments();
        final String noBodyResult;
        final int openBraceIx = functionNodeTextContent.indexOf('{');
        if (openBraceIx == -1) {
            /*
             * This warning will also be triggered by K&R-style function definitions, such as
             *
             * static int
             * newerf (f1, f2)
             * char *f1, *f2;
             * { ... }
             *
             * which will be parsed into SrcML like this:
             *
             * <function>
             *     <type>
             *         <specifier>static</specifier>
             *         <name>int</name>
             *     </type>
             *     <name>newerf</name>
             *     <parameter_list>(
             *          <param><decl><type><name>f1</name></type></decl></param>,
             *          <param><decl><type><name>f2</name></type></decl></param>
             *     )</parameter_list>
             *     <decl_stmt>
             *          <decl><type><name>char</name> *</type><name>f1</name></decl>,
             *          <decl><type ref="prev"/>*<name>f2</name></decl>;
             *     </decl_stmt>
             * </function>
             */
            LOG.warn("Encountered strange function node (no opening `{' found) at " +
                    funcLocForReporting() +
                    ": " + functionNodeTextContent);
            noBodyResult = functionNodeTextContent;
        } else {
            noBodyResult = functionNodeTextContent.substring(0, openBraceIx);
        }
        // Delete line and block comments (yeah, there are some cases where these are part of the function signature ...)
        String noComments = removeComments(noBodyResult, true);

        int loc = SrcMlFolderReader.countLines(noBodyResult);
        if (loc < 1) {
            LOG.info("Computed signature LOC " + loc + " for function " + noComments + ". Adjusting to 1.");
            loc = 1;
        }

        int cStartLoc = FunctionSignatureParser.parseFunctionStartLoc(functionNode);

        return postProcessSignature(noComments, cStartLoc, loc);
    }

    public static String removeComments(String inputString, boolean removeStringAndCharLiterals) {
        // We expect comments in function signatures to be rare, so before we start expensive calculations make sure
        // we may even have a comment.
        boolean mayHaveComments = (inputString.indexOf("/*") != -1) || (inputString.indexOf("//") != -1);
        boolean mayHaveStrings = removeStringAndCharLiterals && (inputString.indexOf('"') != -1);
        boolean mayHaveChars = removeStringAndCharLiterals && (inputString.indexOf('\'') != -1);
        //boolean mayHaveCppDirectives = removeCppDirectives && (inputString.indexOf('#') != -1);

        if (!mayHaveComments && !mayHaveStrings && !mayHaveChars) {
            return inputString;
        }
        final boolean keepStringLiterals = !removeStringAndCharLiterals;
        final boolean keepCharLiterals = !removeStringAndCharLiterals;

        char[] input = toChars(inputString);
        StringBuilder result = new StringBuilder();

        int iRead = 0;
        while (iRead < input.length) {
            char c0 = input[iRead++];
            if (iRead >= input.length) {
                // End of input.  Just copy this last character and stop.
                result.append(c0);
                break;
            }
            switch (c0) {
                case '/': { // possible start of line or block comment.
                    char c1 = input[iRead++];
                    switch (c1) {
                        case '*': // We hit a block comment
                            while (iRead < input.length) {
                                char cNext0 = input[iRead++];
                                if (cNext0 == '*') { // Possible end of block comment
                                    // Make sure we have at least one more character left
                                    if (iRead >= input.length) {
                                        LOG.warn("Possibly malformed block comment in function " + inputString);
                                        break;
                                    }
                                    char cNext1 = input[iRead];
                                    if (cNext1 == '/') { // End of block comment
                                        // Advance the read pointer so we won't look at the / of the end-of-comment marker again.
                                        iRead++;
                                        break;
                                    } else {
                                        // Some other character followed our `*' --> Ignore the `*' but leave the read
                                        // pointer at the character after the `*'.
                                    }
                                } else {
                                    // Some other character within a block comment --> ignore
                                }
                            }
                            break;
                        case '/': // We hit a line comment
                            // Skip all chars up to the next newline or up to the end of input sequence.
                            while (iRead < input.length) {
                                char cNext = input[iRead++];
                                if (cNext == '\n') {
                                    result.append(cNext);
                                    break;
                                }
                            }
                            break;
                        default:
                            // We encountered `/' (c0) followed by some other character (c1).  Thus, the next comment
                            // must start after c1.
                            result.append(c0);
                            result.append(c1);
                    }
                    break;
                }  // End of case '/'
                // We care about string literals, too, because if we don't recognize them correctly, we may
                // mistake a `/*' within a string as the start of a block comment.
                case '"': // Start of a string literal
                {
                    if (keepStringLiterals) {
                        result.append(c0);
                    }
                    boolean endOfLiteral = false;
                    boolean parserError = false;
                    while ((iRead < input.length) && !endOfLiteral && !parserError) {
                        char cNext = input[iRead++];
                        if (keepStringLiterals) {
                            result.append(cNext);
                        }
                        switch (cNext) {
                            case '"':
                                endOfLiteral = true;
                                break;
                            case '\\': // Escape sequence
                                // Read whatever character follows the `\' and continue reading regular string chars
                                // afterwards.
                                if (iRead < input.length) {
                                    char cNextNext = input[iRead++];
                                    if (keepStringLiterals) {
                                        result.append(cNextNext);
                                    }
                                } else {
                                    LOG.warn("Possible malformed escape sequence in string literal in function " + inputString);
                                    parserError = true;
                                }
                                break;
                            default:
                                // Some other character. We already copied the char to the output, so there is nothing
                                // left to do here.
                        }
                    }
                    if (!endOfLiteral) {
                        LOG.warn("Possible non-ending string literal in function " + inputString);
                    }
                    break;
                } // End of string literal
                // I'm not entirely sure whether or not we need to parse character literals, too.  It isn't too
                // difficult, though, and the code is very similar to what we do for string literals (see above).
                case '\'': // Start of a character literal
                {
                    if (keepCharLiterals) {
                        result.append(c0);
                    }
                    boolean endOfLiteral = false;
                    boolean parserError = false;
                    while ((iRead < input.length) && !endOfLiteral && !parserError) {
                        char cNext = input[iRead++];
                        if (keepCharLiterals) {
                            result.append(cNext);
                        }
                        switch (cNext) {
                            case '\'':
                                endOfLiteral = true;
                                break;
                            case '\\': // Escape sequence
                                // Read whatever character follows the `\' and continue reading regular string chars
                                // afterwards.
                                if (iRead < input.length) {
                                    char cNextNext = input[iRead++];
                                    if (keepCharLiterals) {
                                        result.append(cNextNext);
                                    }
                                } else {
                                    LOG.warn("Possible malformed escape sequence in character literal in function " + inputString);
                                    parserError = true;
                                }
                                break;
                            default:
                                // Some other character. We already copied the char to the output, so there is nothing
                                // left to do here.
                        }
                    }
                    if (!endOfLiteral) {
                        LOG.warn("Possible non-ending character literal in function " + inputString);
                    }
                    break;
                } // End of char literal
                /*
                case '#':
                    if (removeCppDirectives && isBeginningOfLine(input, iRead - 1)) {

                        break;
                    }
                    // HINT, 2017-03-23, wf: Yes, we want fall-through in case removing cpp directives is not requested.
                    */
                default:
                    result.append(c0);
            }
        }

        return result.toString();
    }

    private static char[] toChars(String s) {
        final int len = s.length();
        final char[] result = new char[len];
        s.getChars(0, len, result, 0);
        return result;
    }

//    static boolean isBeginningOfLine(char[] input, int iRead) {
//        if (iRead == 0) return true;
//        final int posBefore = iRead - 1;
//        if (posBefore < input.length) {
//            final char previousChar = input[posBefore];
//            return ((previousChar == '\n') || (previousChar == '\r'));
//        }
//        return false;
//    }

    public ParsedFunctionSignature parseFunctionSignature() {
        // get the text content of the node (signature + method content),
        // and remove method content until beginning of block

        final int openBraceIx = functionNodeTextContent.indexOf('{');
        if (openBraceIx == -1) {
            /*
             * This may happen for K&R-style function definitions, such as
             *
             * static int
             * newerf (f1, f2)
             * char *f1, *f2;
             * { ... }
             */
            try {
                ParsedFunctionSignature signature = parseRegularFunctionSignature();
                if (debugParseExceptions) {
                    LOG.debug("Successfully parsed function signature using XPath: `" + signature + "' parsed from " + prettyPrintFunctionNodeOrChild(functionNode));
                }
                return signature;
            } catch (FunctionSignatureParseException parseEx) {
                if (debugParseExceptions) {
                    LOG.debug("Could not parse function signature (going to fallback): " + prettyPrintFunctionNodeOrChild(functionNode), parseEx);
                }
            }
        }

        return parseFunctionSignatureQuickAndDirty();
    }

    private void deleteFunctionBody(Node node) {
        try {
            NodeList blocks = (NodeList) ensureXPath().evaluate(".//block", node, XPathConstants.NODESET);
            if (blocks != null) {
                deleteNodes(blocks);
            }
        } catch (XPathExpressionException e) {
            // Don't care
        }
    }

    private void deleteNodes(NodeList nodeList) {
        final int len = nodeList.getLength();
        for (int i = 0; i < len; i++) {
            Node node = nodeList.item(i);
            if (node != null) {
                Node nodeParent = node.getParentNode();
                if (nodeParent != null) {
                    nodeParent.removeChild(node);
                }
            }
        }
    }

    private void deleteComments(Node node) {
        try {
            NodeList comments = (NodeList) ensureXPath().evaluate(".//comment", node, XPathConstants.NODESET);
            if (comments != null) {
                deleteNodes(comments);
            }
        } catch (XPathExpressionException e) {
            // Don't care
        }
    }

    private String prettyPrintFunctionNodeOrChild(Node node) {
        node = node.cloneNode(true);
        deleteComments(node);
        deleteFunctionBody(node);

        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer transformer = null;
        try {
            transformer = tf.newTransformer();
        } catch (TransformerConfigurationException e) {
            LOG.warn("Failed to create transformer for pretty-printing XML", e);
            return node.getTextContent();
        }
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        StringWriter result = new StringWriter();
        try {
            transformer.transform(new DOMSource(node), new StreamResult(result));
        } catch (TransformerException e) {
            LOG.warn("Problem while pretty-printing XML", e);
            return node.getTextContent();
        }
        return result.toString();
    }

    private ParsedFunctionSignature postProcessSignature(String signature, int cStartLoc, int loc) {
        // Squeeze multiple space signs into a single space
        String trimmed = normalizeWhitespace(signature);
        // Determine start location
        return new ParsedFunctionSignature(trimmed, cStartLoc, loc);
    }

    protected static String normalizeWhitespace(String signature) {
        if (signature == null || signature.isEmpty()) return signature;

        char[] chars = toChars(signature);

        // Trim whitespace off beginning and end
        int last = signature.length() - 1;
        while (last >= 0) {
            char c = chars[last];
            if (Character.isWhitespace(c) || (c == '\\')) {
                last--;
            } else break;
        }

        StringBuilder result = new StringBuilder(last + 1);

        int i = 0;
        // Last character that we inserted into the result
        char lastChar = ' ';
        while (i <= last) {
            char c = chars[i++];

            // Skip '\' followed by newline and treat it as if the line would just continue.
            if ((c == '\\') && (i <= last) && (chars[i] == '\n')) {
                i++;
                continue;
            }

            // Convert all whitespace characters into a single space
            if (Character.isWhitespace(c)) c = ' ';

            boolean requireSpaceAsNextChar = false;
            switch (c) {
                // No space after another space, and no space after an opening parenthesis
                case ' ':
                    switch (lastChar) {
                        case ' ':
                        case '(':
                            continue;
                    }
                    result.append(c);
                    lastChar = c;
                    break;
                case '(': // No space before an opening parenthesis
                    if (lastChar == ' ') {
                        int len = result.length();
                        if (len > 0) {
                            result.deleteCharAt(len - 1);
                        }
                    }
                    result.append(c);
                    lastChar = c;
                    break;
                // Remove potential space character before a comma or a closing parenthesis,
                // and require a following space
                case ',':
                case ')':
                    if (lastChar == ' ') {
                        int len = result.length();
                        if (len > 0) {
                            result.deleteCharAt(len - 1);
                        }
                    }
                    result.append(c);
                    lastChar = c;
                    requireSpaceAsNextChar = true;
                    break;
                case '*': // Require spaces before and after '*'
                    if (lastChar != ' ') {
                        result.append(' ');
                    }
                    result.append(c);
                    lastChar = c;
                    requireSpaceAsNextChar = true;
                    break;
                default:
                    result.append(c);
                    lastChar = c;
            }


            if (requireSpaceAsNextChar && (i <= last)) {
                result.append(' ');
                lastChar = ' ';
            }
        }

        return result.toString();
    }

    private Node getNodeOrDie(Node nodeOfInterest, String xpathExpression) throws FunctionSignatureParseException {
        Optional<Node> r = getOptionalNode(nodeOfInterest, xpathExpression);
        if (r.isPresent()) return r.get();
        else {
            if (debugParseExceptions) {
                throw new FunctionSignatureParseException("Missing node `" + xpathExpression + "' in "
                        + prettyPrintFunctionNodeOrChild(nodeOfInterest) + " (" + funcLocForReporting() + ")");
            } else {
                throw new FunctionSignatureParseException();
            }
        }
    }

    private Optional<Node> getOptionalNode(Node nodeOfInterest, String xpathExpression) throws FunctionSignatureParseException {
        Node result = null;
        try {
            result = (Node) ensureXPath().evaluate(xpathExpression, nodeOfInterest, XPathConstants.NODE);
        } catch (XPathExpressionException e) {
            if (debugParseExceptions) {
                throw new FunctionSignatureParseException("Error finding node `" + xpathExpression + "' in "
                        + prettyPrintFunctionNodeOrChild(nodeOfInterest) + " (" + funcLocForReporting() + ")");
            } else {
                throw new FunctionSignatureParseException(e);
            }
        }

        return Optional.ofNullable(result);
    }

    private List<Node> getPossiblyEmptyNodeList(Node nodeOfInterest, String xpathExpression) throws FunctionSignatureParseException {
        NodeList result = null;
        try {
            result = (NodeList) ensureXPath().evaluate(xpathExpression,
                    nodeOfInterest, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            if (debugParseExceptions) {
                throw new FunctionSignatureParseException("Error finding nodes matching `" + xpathExpression + "' in " +
                        nodeOfInterest.getTextContent() + " (" + funcLocForReporting() + ")");
            } else {
                throw new FunctionSignatureParseException(e);
            }
        }

        if (result == null) return Collections.emptyList();

        List<Node> resultList = new ArrayList<>();
        for (int iNode = 0; iNode < result.getLength(); iNode++) {
            resultList.add(result.item(iNode));
        }

        return resultList;
    }

    private String funcLocForReporting() {
        int startLoc = parseFunctionStartLoc(functionNode);
        return filePath.pathKey + ":" + startLoc;
    }

    /**
     * @param funcNode An XML node from the SrcML representation of a C file, as parsed by Skunk
     * @return Start location of the function node.  <em>Note:</em> This count in 1-based, as is the value returned by
     * {@link Method#start1}
     */
    public static int parseFunctionStartLoc(Node funcNode) {
        return get1BasedNodeLineNumber(funcNode);
    }

    private static int get1BasedNodeLineNumber(Node node) {
        int xmlStartLoc = PositionalXmlReader.getElementLineNumberAsIs((Element) node);
        // The srcML representation starts with a one-line XML declaration, which we subtract here.
        return xmlStartLoc - 1;
    }

    //        private XPath ensureXPath() {
//            if (xPath == null) {
//                xPath = XPathFactory.newInstance().newXPath();
//            }
//            return xPath;
//        }
    private XPath ensureXPath() {
        return tlXPath.get();
    }
}
