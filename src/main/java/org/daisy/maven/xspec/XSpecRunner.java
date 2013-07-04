/**
 * Copyright (C) 2013 The DAISY Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.daisy.maven.xspec;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.SourceLocator;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.MessageListener;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XPathCompiler;
import net.sf.saxon.s9api.XdmAtomicValue;
import net.sf.saxon.s9api.XdmDestination;
import net.sf.saxon.s9api.XdmNode;
import net.sf.saxon.s9api.XdmValue;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;

import org.apache.xml.resolver.CatalogManager;
import org.apache.xml.resolver.tools.CatalogResolver;

import com.google.common.base.Charsets;
import com.google.common.base.Stopwatch;
import com.google.common.io.Files;
import com.google.common.io.InputSupplier;
import com.google.common.io.Resources;

public class XSpecRunner {

	private final static String XSPEC_NAMESPACE = "http://www.jenitennison.com/xslt/xspec";
	private final static QName XSPEC_MAIN_TEMPLATE = new QName("x",
			XSPEC_NAMESPACE, "main");
	private static final String XSPEC_CSS_NAME = "xspec-report.css";
	private static final QName XSPEC_CSS_URI_PARAM = new QName("report-css-uri");
	private static final XdmValue XSPEC_CSS_URI = new XdmAtomicValue(
			XSPEC_CSS_NAME);
	private static final QName JUNIT_NAME_PARAM = new QName("name");;
	private static final QName JUNIT_TIME_PARAM = new QName("time");;

	private Processor processor;
	private URIResolver defaultResolver;
	private XPathCompiler xpathCompiler;
	private XsltExecutable xspecCompilerLoader;
	private XsltExecutable xspecHtmlFormatterLoader;
	private XsltExecutable xspecJUnitFormatterLoader;
	private InputSupplier<InputStream> cssSupplier;

	public XSpecRunner() {
		try {
			init();
		} catch (SaxonApiException e) {
			throw new IllegalStateException(e);
		}
	}

	public TestResults run(Map<String, File> tests, File reportDir) {
		TestResults.Builder builder = new TestResults.Builder("");
		for (Map.Entry<String, File> test : tests.entrySet()) {
			builder.addSubResults(runSingle(test.getKey(), test.getValue(),
					reportDir));
		}
		return builder.build();
	}

	private TestResults runSingle(String testName, File testFile, File reportDir) {
		// Prepare the reporters
		File textReport = new File(reportDir, "OUT-" + testName + ".txt");
		PrintWriter writer = null;
		try {
			writer = new PrintWriter(
					Files.newWriter(textReport, Charsets.UTF_8));
		} catch (FileNotFoundException e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
		SaxonReporter saxonReporter = new SaxonReporter(writer);

		XdmDestination xspecTestResult = new XdmDestination();
		XdmDestination xspecTestCompiled = new XdmDestination();
		SaxonApiException executionException = null;

		Stopwatch stopwatch = new Stopwatch().start();
		report("Running " + testName, writer);

		try {
			// Compile the XSPec test into an executable XSLT
			XsltTransformer xspecCompiler = xspecCompilerLoader.load();
			xspecCompiler.setSource(new StreamSource(testFile));
			xspecCompiler.setDestination(xspecTestCompiled);
			xspecCompiler.setErrorListener(saxonReporter);
			xspecCompiler.setMessageListener(saxonReporter);
			xspecCompiler.transform();

			// Create a new URI resolver if a mock catalog is present
			File catalog = new File(testFile.getParentFile(), "catalog.xml");
			URIResolver testResolver = defaultResolver;
			if (catalog.exists()) {
				CatalogManager catman = new CatalogManager();
				catman.setCatalogFiles(catalog.getPath());
				testResolver = new CatalogResolver(catman);
			}

			// Run the compiled XSpec test
			XsltCompiler xspecTestCompiler = processor.newXsltCompiler();
			xspecTestCompiler.setURIResolver(testResolver);
			processor.getUnderlyingConfiguration().setErrorListener(saxonReporter);
			XsltTransformer xspecTestRunner = xspecTestCompiler.compile(
					xspecTestCompiled.getXdmNode().asSource()).load();
			xspecTestRunner.setInitialTemplate(XSPEC_MAIN_TEMPLATE);
			xspecTestRunner.setDestination(xspecTestResult);
			xspecTestRunner.setErrorListener(saxonReporter);
			xspecTestRunner.setMessageListener(saxonReporter);
			xspecTestRunner.setURIResolver(testResolver);
			xspecTestRunner.transform();

		} catch (SaxonApiException e) {
			report(e.getMessage(), writer);
			e.printStackTrace(writer);
			executionException = e;
		}

		stopwatch.stop();

		TestResults result = (executionException == null) ? XSpecResultBuilder
				.fromReport(testName, xspecTestResult.getXdmNode(),
						xpathCompiler, stopwatch.toString())
				: XSpecResultBuilder.fromException(testName,
						executionException, stopwatch.toString());

		report(result.toString(), writer);
		
		writer.close();

		if (result.getErrors() == 0) {
			try {
				// Write XSpec report
				File xspecReport = new File(reportDir, "XSPEC-" + testName
						+ ".xml");
				new Serializer(xspecReport).serializeNode(xspecTestResult
						.getXdmNode());

				// Write HTML report
				File css = new File(reportDir, XSPEC_CSS_NAME);
				if (!css.exists()) {
					Files.copy(cssSupplier, css);
				}
				File htmlReport = new File(reportDir, "HTML-" + testName
						+ ".html");
				XsltTransformer htmlFormatter = xspecHtmlFormatterLoader.load();
				htmlFormatter
						.setSource(xspecTestResult.getXdmNode().asSource());
				htmlFormatter.setParameter(XSPEC_CSS_URI_PARAM, XSPEC_CSS_URI);
				htmlFormatter.setDestination(new Serializer(htmlReport));
				htmlFormatter.setMessageListener(SaxonSinkReporter.INSTANCE);
				htmlFormatter.transform();

				// Write Surefire report
				File surefireReport = new File(reportDir, "TEST-" + testName
						+ ".xml");
				XsltTransformer junitFormatter = xspecJUnitFormatterLoader
						.load();
				junitFormatter.setSource(xspecTestResult.getXdmNode()
						.asSource());
				junitFormatter.setDestination(new Serializer(surefireReport));
				junitFormatter.setParameter(JUNIT_NAME_PARAM,
						new XdmAtomicValue(testName));
				junitFormatter.setParameter(
						JUNIT_TIME_PARAM,
						new XdmAtomicValue(stopwatch
								.elapsed(TimeUnit.MILLISECONDS) / 1000d));
				junitFormatter.transform();
			} catch (SaxonApiException e) {
				throw new RuntimeException(e);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return result;
	}

	private void init() throws SaxonApiException {
		System.setProperty("xml.catalog.ignoreMissing", "true");
		processor = new Processor(false);
		defaultResolver = processor.getUnderlyingConfiguration()
				.getURIResolver();

		XsltCompiler xsltCompiler = processor.newXsltCompiler();
		// Initialize the XSpec compiler
		xspecCompilerLoader = xsltCompiler.compile(new StreamSource(
				XSpecRunner.class.getResource(
						"/xspec/compiler/generate-xspec-tests.xsl")
						.toExternalForm()));

		// Initialize the XSpec report formatter
		xspecHtmlFormatterLoader = xsltCompiler.compile(new StreamSource(
				XSpecRunner.class.getResource(
						"/xspec/reporter/format-xspec-report.xsl")
						.toExternalForm()));

		// Initialize the JUnit report formatter
		xspecJUnitFormatterLoader = xsltCompiler.compile(new StreamSource(
				XSpecRunner.class.getResource(
						"/xspec-extra/format-junit-report.xsl")
						.toExternalForm()));

		// Configure the XPath compiler used to parse the XSpec report
		xpathCompiler = processor.newXPathCompiler();
		xpathCompiler.setCaching(true);
		xpathCompiler.declareNamespace("", XSPEC_NAMESPACE);

		// Input supplier for the report CSS
		cssSupplier = Resources.newInputStreamSupplier(XSpecRunner.class
				.getResource("/xspec/reporter/test-report.css"));
	}

	private static void report(String message, PrintWriter writer) {
		System.out.println(message);
		writer.println(message);
	}

	private static class SaxonReporter implements ErrorListener,
			MessageListener {

		private final PrintWriter writer;

		public SaxonReporter(PrintWriter writer) {
			this.writer = writer;
		}

		public void warning(TransformerException exception)
				throws TransformerException {
			writer.println(exception.getMessage());
		}

		public void error(TransformerException exception)
				throws TransformerException {
			writer.println(exception.getMessage());
		}

		public void fatalError(TransformerException exception)
				throws TransformerException {
			writer.println(exception.getMessage());
		}

		public void message(XdmNode content, boolean terminate,
				SourceLocator locator) {
			writer.println(content);
		}

	}
	private static class SaxonSinkReporter implements ErrorListener,
	MessageListener {
		
		static SaxonSinkReporter INSTANCE = new SaxonSinkReporter();
		
		private SaxonSinkReporter() {
		}
		
		public void warning(TransformerException exception)
				throws TransformerException {
		}
		
		public void error(TransformerException exception)
				throws TransformerException {
		}
		
		public void fatalError(TransformerException exception)
				throws TransformerException {
		}
		
		public void message(XdmNode content, boolean terminate,
				SourceLocator locator) {
		}
		
	}
}
