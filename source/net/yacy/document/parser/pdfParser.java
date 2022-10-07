//pdfParser.java
//------------------------
//part of YaCy
//(C) by Michael Peter Christen; mc@yacy.net
//first published on http://www.anomic.de
//Frankfurt, Germany, 2005
//
//this file is contributed by Martin Thelian
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package net.yacy.document.parser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;

import net.yacy.cora.document.encoding.UTF8;
import net.yacy.cora.document.id.AnchorURL;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.document.id.MultiProtocolURL;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.document.AbstractParser;
import net.yacy.document.Document;
import net.yacy.document.Parser;
import net.yacy.document.VocabularyScraper;
import net.yacy.kelondro.io.CharBuffer;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.MemoryControl;


public class pdfParser extends AbstractParser implements Parser {

    public static boolean individualPages = false;
    public static String individualPagePropertyname = "page";
    
    public pdfParser() {
        super("Acrobat Portable Document Parser");
        this.SUPPORTED_EXTENSIONS.add("pdf");
        this.SUPPORTED_MIME_TYPES.add("application/pdf");
        this.SUPPORTED_MIME_TYPES.add("application/x-pdf");
        this.SUPPORTED_MIME_TYPES.add("application/acrobat");
        this.SUPPORTED_MIME_TYPES.add("applications/vnd.pdf");
        this.SUPPORTED_MIME_TYPES.add("text/pdf");
        this.SUPPORTED_MIME_TYPES.add("text/x-pdf");
    }

    @Override
    public Document[] parse(
            final DigestURL location,
            final String mimeType,
            final String charset,
            final VocabularyScraper scraper, 
            final int timezoneOffset,
            final InputStream source) throws Parser.Failure, InterruptedException {

        // check memory for parser
        if (!MemoryControl.request(200 * 1024 * 1024, false))
            throw new Parser.Failure("Not enough Memory available for pdf parser: " + MemoryControl.available(), location);

        // create a pdf parser
        PDDocument pdfDoc;
        try {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY); // the pdfparser is a big pain
            MemoryUsageSetting mus = MemoryUsageSetting.setupMixed(200*1024*1024);
            pdfDoc = PDDocument.load(source, mus);
        } catch (final IOException e) {
            throw new Parser.Failure(e.getMessage(), location);
        } finally {
            Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
        }

        if (pdfDoc.isEncrypted()) {
            final AccessPermission perm = pdfDoc.getCurrentAccessPermission();
            if (perm == null || !perm.canExtractContent()) {
                try {pdfDoc.close();} catch (final IOException ee) {}
                throw new Parser.Failure("Document is encrypted and cannot be decrypted", location);
            }
        }

        // extracting some metadata
        PDDocumentInformation info = pdfDoc.getDocumentInformation();
        String docTitle = null, docSubject = null, docAuthor = null, docPublisher = null, docKeywordStr = null;
        Date docDate = new Date();
        if (info != null) {
            docTitle = info.getTitle();
            docSubject = info.getSubject();
            docAuthor = info.getAuthor();
            docPublisher = info.getProducer();
            if (docPublisher == null || docPublisher.isEmpty()) docPublisher = info.getCreator();
            docKeywordStr = info.getKeywords();
            if (info.getModificationDate() != null) docDate = info.getModificationDate().getTime();
            // unused:
            // info.getTrapped());
        }
        info = null;

        if (docTitle == null || docTitle.isEmpty()) {
            docTitle = MultiProtocolURL.unescape(location.getFileName());
        }
        if (docTitle == null) {
            docTitle = docSubject;
        }
        String[] docKeywords = null;
        if (docKeywordStr != null) {
            docKeywords = docKeywordStr.split(" |,");
        }
        
        Document[] result = null;
        try {
            // get the links
        	final List<Collection<AnchorURL>> pdflinks = extractPdfLinks(pdfDoc);
            
            // get the fulltext (either per document or for each page)
            final PDFTextStripper stripper = new PDFTextStripper(/*StandardCharsets.UTF_8.name()*/);

            if (individualPages) {
                // this is a hack which stores individual pages of the source pdf into individual index documents
                // the new documents will get a virtual link with a post argument page=X appended to the original url
                
                // collect text
                int pagecount = pdfDoc.getNumberOfPages();
                String[] pages = new String[pagecount];
                for (int page = 1; page <= pagecount; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    pages[page - 1] = stripper.getText(pdfDoc);
                    //System.out.println("PAGE " + page + ": " + pages[page - 1]);
                }
                
                // create individual documents for each page
                assert pages.length == pdflinks.size() : "pages.length = " + pages.length + ", pdflinks.length = " + pdflinks.size();
                result = new Document[Math.min(pages.length, pdflinks.size())];
                String loc = location.toNormalform(true);
                for (int page = 0; page < result.length; page++) {                    
                    result[page] = new Document(
                            new AnchorURL(loc + (loc.indexOf('?') > 0 ? '&' : '?') + individualPagePropertyname + '=' + (page + 1)), // these are virtual new pages; we cannot combine them with '#' as that would be removed when computing the urlhash
                            mimeType,
                            StandardCharsets.UTF_8.name(),
                            this,
                            null,
                            docKeywords,
                            singleList(docTitle),
                            docAuthor,
                            docPublisher,
                            null,
                            null,
                            0.0d, 0.0d,
                            pages == null || page > pages.length ? new byte[0] : UTF8.getBytes(pages[page]),
                            pdflinks == null || page >= pdflinks.size() ? null : pdflinks.get(page),
                            null,
                            null,
                            false,
                            docDate);
                }
            } else {
                // collect the whole text at once
                final CharBuffer writer = new CharBuffer(odtParser.MAX_DOCSIZE);
                byte[] contentBytes = new byte[0];
                stripper.setEndPage(3); // get first 3 pages (always)
                writer.append(stripper.getText(pdfDoc));
                contentBytes = writer.getBytes(); // remember text in case of interrupting thread

                if (pdfDoc.getNumberOfPages() > 3) { // spare creating/starting thread if all pages read
                    stripper.setStartPage(4); // continue with page 4 (terminated, resulting in no text)
                    stripper.setEndPage(Integer.MAX_VALUE); // set to default
                    // we start the pdf parsing in a separate thread to ensure that it can be terminated
                    final PDDocument pdfDocC = pdfDoc;
                    final Thread t = new Thread("pdfParser.getText:" + location) {
                        @Override
                        public void run() {
                            try {
                                writer.append(stripper.getText(pdfDocC));
                            } catch (final Throwable e) {}
                        }
                    };
                    t.start();
                    t.join(3000); // pdfbox likes to forget to terminate ... (quite often)
                    if (t.isAlive()) t.interrupt();
                    contentBytes = writer.getBytes(); // get final text before closing writer
                    writer.close(); // free writer resources
                }
                
                Collection<AnchorURL> pdflinksCombined = new HashSet<AnchorURL>();
                for (Collection<AnchorURL> pdflinksx: pdflinks) if (pdflinksx != null) pdflinksCombined.addAll(pdflinksx);
                result = new Document[]{new Document(
                        location,
                        mimeType,
                        StandardCharsets.UTF_8.name(),
                        this,
                        null,
                        docKeywords,
                        singleList(docTitle),
                        docAuthor,
                        docPublisher,
                        null,
                        null,
                        0.0d, 0.0d,
                        contentBytes,
                        pdflinksCombined,
                        null,
                        null,
                        false,
                        docDate)};
            }         
        } catch (final Throwable e) {
            //throw new Parser.Failure(e.getMessage(), location);
        } finally {
            try {pdfDoc.close();} catch (final Throwable e) {}
        }

        // clear cached resources in pdfbox.
        pdfDoc = null;
        clearPdfBoxCaches();
        
        return result;
    }

    /**
     * extract clickable links from pdf
     * @param pdf the document to parse
     * @return all detected links
     */
    private List<Collection<AnchorURL>> extractPdfLinks(final PDDocument pdf) {
        List<Collection<AnchorURL>> linkCollections = new ArrayList<>(pdf.getNumberOfPages());
        for (PDPage page : pdf.getPages()) {
            final Collection<AnchorURL> pdflinks = new ArrayList<AnchorURL>();
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations != null) {
                    for (PDAnnotation pdfannotation : annotations) {
                        if (pdfannotation instanceof PDAnnotationLink) {
                            PDAction link = ((PDAnnotationLink)pdfannotation).getAction();
                            if (link != null && link instanceof PDActionURI) {
                                PDActionURI pdflinkuri = (PDActionURI) link;
                                String uristr = pdflinkuri.getURI();
                                AnchorURL url = new AnchorURL(uristr);
                                pdflinks.add(url);
                            }
                        }
                    }
                }
            } catch (IOException ex) {}
            linkCollections.add(pdflinks);
        }
        return linkCollections;
    }

    /**
     * Clean up cache resources allocated by PDFBox that would otherwise not be released.
     */
    public static void clearPdfBoxCaches() {
		/*
		 * Prior to pdfbox 2.0.0 font cache occupied > 80MB RAM for a single pdf and
		 * then stayed forever (detected in YaCy with pdfbox version 1.2.1). The
		 * situation is now from far better, but one (unnecessary?) cache structure in
		 * the COSName class still needs to be explicitely cleared.
		 */
    	
		// History of related issues :
    	// http://markmail.org/thread/quk5odee4hbsauhu
		// https://issues.apache.org/jira/browse/PDFBOX-313 
		// https://issues.apache.org/jira/browse/PDFBOX-351
		// https://issues.apache.org/jira/browse/PDFBOX-441
    	// https://issues.apache.org/jira/browse/PDFBOX-2200
    	// https://issues.apache.org/jira/browse/PDFBOX-2149
    	
        COSName.clearResources();
        
		/*
		 * Prior to PDFBox 2.0.0, clearResources() function had to be called on the
		 * org.apache.pdfbox.pdmodel.font.PDFont class and its children. After version
		 * 2.0.0, there is no more such a function in PDFont class as font cache is
		 * handled differently and hopefully more properly.
		 */
    }

    /**
     * test
     * @param args
     */
    public static void main(final String[] args) {
        if (args.length > 0 && args[0].length() > 0) {
            // file
            final File pdfFile = new File(args[0]);
            if(pdfFile.canRead()) {

                System.out.println(pdfFile.getAbsolutePath());
                final long startTime = System.currentTimeMillis();

                // parse
                final AbstractParser parser = new pdfParser();
                Document document = null;
                FileInputStream inStream = null; 
                try {
                	inStream = new FileInputStream(pdfFile);
                    document = Document.mergeDocuments(null, "application/pdf", parser.parse(null, "application/pdf", null, new VocabularyScraper(), 0, inStream));
                } catch (final Parser.Failure e) {
                    System.err.println("Cannot parse file " + pdfFile.getAbsolutePath());
                    ConcurrentLog.logException(e);
                } catch (final InterruptedException e) {
                    System.err.println("Interrupted while parsing!");
                    ConcurrentLog.logException(e);
                } catch (final NoClassDefFoundError e) {
                    System.err.println("class not found: " + e.getMessage());
                } catch (final FileNotFoundException e) {
                    ConcurrentLog.logException(e);
                } finally {
                	if(inStream != null) {
                		try {
                			inStream.close();
                		} catch(IOException e) {
                			System.err.println("Could not close input stream on file " + pdfFile);
                		}
                	}
                }

                // statistics
                System.out.println("\ttime elapsed: " + (System.currentTimeMillis() - startTime) + " ms");

                // output
                if (document == null) {
                    System.out.println("\t!!!Parsing without result!!!");
                } else {
                    System.out.println("\tParsed text with " + document.getTextLength() + " chars of text and " + document.getAnchors().size() + " anchors");
                    InputStream textStream = document.getTextStream();
                    try {
                        // write file
                        FileUtils.copy(textStream, new File("parsedPdf.txt"));
                    } catch (final IOException e) {
                        System.err.println("error saving parsed document");
                        ConcurrentLog.logException(e);
                    } finally {
                    	try {
                        	if(textStream != null) {
                        		/* textStream can be a FileInputStream : we must close it to ensure releasing system resource */
                        		textStream.close();
                        	}
						} catch (IOException e) {
							ConcurrentLog.warn("PDFPARSER", "Could not close text input stream");
						}
                    }
                }
            } else {
                System.err.println("Cannot read file "+ pdfFile.getAbsolutePath());
            }
        } else {
            System.out.println("Please give a filename as first argument.");
        }
    }

}
