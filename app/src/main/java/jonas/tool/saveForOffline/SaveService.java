package jonas.tool.saveForOffline;

import android.app.*;
import android.content.*;
import android.widget.*;
import android.util.*;
import android.webkit.*;
import android.graphics.*;
import java.io.*;
import android.database.sqlite.*;
import android.view.View.*;
import android.os.*;
import android.preference.*;
import android.os.Process;
import java.net.URL;
import java.util.List;
import java.util.StringTokenizer;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.net.HttpURLConnection;
import org.jsoup.nodes.Document;
import org.jsoup.Jsoup;
import org.jsoup.select.Elements;
import org.jsoup.nodes.Element;
import java.net.MalformedURLException;

public class SaveService extends IntentService {

	
	private String filelocation;
	private String destinationDirectory;
	private String thumbnail;
	private String origurl;
	
	private String uaString;

	private boolean thumbnailWasSaved = false;
	
	private boolean wasAddedToDb = false;
	
	private boolean errorOccurred = false;
	private String errorDescription = "";
	
	private int failCount = 0;

	private int notification_id = 1;
	private Notification.Builder mBuilder;
	private NotificationManager mNotificationManager;
	
	public SaveService () {
		super("SaveService");
	}

	@Override
	public void onHandleIntent(Intent intent) {
		
		errorDescription = "";
		wasAddedToDb = false;
		errorOccurred = false;
		
		thumbnailWasSaved = false;
		

		mBuilder = new Notification.Builder(SaveService.this)
			.setContentTitle("Saving webpage...")
			.setTicker("Saving webpage...")
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setProgress(0,0, true)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setPriority(Notification.PRIORITY_HIGH)
			.setContentText("Save in progress: getting ready...");
			
		mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(notification_id, mBuilder.build());
		

		SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(SaveService.this);
		String ua = sharedPref.getString("user_agent", "mobile");

		if (ua.equals("desktop")) {
			uaString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.517 Safari/537.36";

		} else if (ua.equals("ipad")) {
			uaString = "iPad ipad safari";

		} else {
			uaString = "android";
		}

		DirectoryHelper dh = new DirectoryHelper();
		filelocation = dh.getFileLocation();
		destinationDirectory = dh.getUnpackedDir();
		thumbnail = dh.getThumbnailLocation();
		
		origurl = intent.getStringExtra("origurl");


		Toast.makeText(SaveService.this, "Saving page...", Toast.LENGTH_SHORT).show();


		// This is the important code :)  
		// Without it the view will have a dimension of 0,0 and the bitmap will be null       
		
		
		
		try {
			grabPage(origurl, destinationDirectory);
		} catch (Exception e) {
			return;
		}
		
		addToDb();
		
		Intent i = new Intent(this, ScreenshotService.class);
		i.putExtra("origurl", "file://" + destinationDirectory + "index.html");
		i.putExtra("thumbnail", thumbnail);
		startService(i);
		
		mBuilder
			.setContentTitle("Save completed.")
			.setTicker("Saved: " + GrabUtility.title)
			.setSmallIcon(R.drawable.ic_notify_save)
			.setOngoing(false)
			.setProgress(0,0,false)
			.setOnlyAlertOnce(false)
			.setPriority(Notification.PRIORITY_LOW)
			.setContentText(GrabUtility.title);
		mNotificationManager.notify(notification_id, mBuilder.build());
	}
	
	private void notifyProgress(String filename, int maxProgress, int progress) {
		mBuilder
			.setContentText(filename)
			.setProgress(maxProgress, progress, false);
		mNotificationManager.notify(notification_id, mBuilder.build());
	}
	
	
	private void notifyError(String message, String extraMessage) {
		if (message != null) {
		mBuilder
			.setContentText(extraMessage)
			.setOnlyAlertOnce(false)
			.setTicker("Could not save page!")
			.setSmallIcon(android.R.drawable.stat_sys_warning)
			.setContentTitle(message)
			.setProgress(0,0,false);
			
		} else {
			mBuilder
			.setContentText(extraMessage);
		}
		mNotificationManager.notify(notification_id, mBuilder.build());
		
		
	}

	private void addToDb() {

		//dont want to put it in the database multiple times
		if (wasAddedToDb) return;

		DbHelper mHelper = new DbHelper(SaveService.this);
		SQLiteDatabase dataBase = mHelper.getWritableDatabase();
		ContentValues values=new ContentValues();

		
		values.put(DbHelper.KEY_FILE_LOCATION, destinationDirectory + "index.html");

		values.put(DbHelper.KEY_TITLE, GrabUtility.title);
		values.put(DbHelper.KEY_THUMBNAIL, thumbnail);
		values.put(DbHelper.KEY_ORIG_URL, origurl);

		//insert data into database
		dataBase.insert(DbHelper.TABLE_NAME, null, values);

		//close database
		dataBase.close();

		wasAddedToDb = true;
	}
	
	private void grabPage(String url, String outputDirPath) throws Exception {

		GrabUtility.filesToGrab.clear();
		GrabUtility.title = null;
		GrabUtility.grabbedFiles.clear();
		
		int i = 0;
		
        if(url == null || outputDirPath == null){
        	notifyError("Page not saved", "There was an internal error, this is a bug, so please report it.");
	        throw new IllegalArgumentException();
		}
		if(!url.startsWith("http://") && !url.startsWith("https://")){
			notifyError("Bad url","URL to save must start with http:// or https://");
			throw new IllegalArgumentException("url does not have protocol part. Must start with http:// or https://");
		}

		URL obj = new URL(url);
		
		File outputDir = new File(outputDirPath);
		
		if(outputDir.exists() && outputDir.isFile()){
			System.out.println("output directory path is wrong, please provide some directory path");
			return;
		} else if (!outputDir.exists()){
			outputDir.mkdirs();
		}
		
		downloadMainHtmlAndParseLinks(url, outputDirPath);

		//Links to visit ->
		String tempEntry = null;
		int linksToGrabSize;
		synchronized (GrabUtility.filesToGrab) {
			linksToGrabSize = GrabUtility.filesToGrab.size();
			System.out.println("Total filesToGrab - "+linksToGrabSize);
		}

		for (i=0; i<linksToGrabSize; i++) {
			System.out.println("Value of i - "+i);
			tempEntry = null;

			synchronized (GrabUtility.filesToGrab) {
				if(GrabUtility.filesToGrab.size() > i){
					tempEntry = GrabUtility.filesToGrab.get(i);
					obj = new URL(tempEntry);
					if(!GrabUtility.isURLAlreadyGrabbed(tempEntry)){
						getExtraFile(obj, outputDir);
						notifyProgress("Saving file: " + tempEntry.substring(tempEntry.lastIndexOf("/") + 1), GrabUtility.filesToGrab.size(), i);
					}
					
					
				}
				
				
			}
			linksToGrabSize = GrabUtility.filesToGrab.size();
			
		}

	}
	
	private void downloadMainHtmlAndParseLinks (String url, String outputDir) throws IOException {
		FileOutputStream fop = null;
		BufferedReader in = null;
		HttpURLConnection conn = null;
		File outputFile = null;
		InputStream is = null;
		notifyProgress("Downloading main HTML file", 100, 1);
		try {
			
			URL obj = new URL(url);
			String filename = "index.html";

			//Output file name
			outputFile = new File(outputDir, filename);

			conn = (HttpURLConnection) obj.openConnection();
			conn.setReadTimeout(5000);
			conn.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
			conn.addRequestProperty("User-Agent", uaString);
			conn.addRequestProperty("Referer", "google.com");

			boolean redirect = false;

			// normally, 3xx is redirect
			int status = conn.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_NOT_FOUND) {
				if (status == HttpURLConnection.HTTP_MOVED_TEMP
					|| status == HttpURLConnection.HTTP_MOVED_PERM
					|| status == HttpURLConnection.HTTP_SEE_OTHER){
					redirect = true;
				}else{
					notifyError("Could not save page", "Failed to download main HTML file. HTTP status code: " + status);
					throw new IOException();
				}
			}

			if (redirect) {
				// get redirect url from "location" header field
				String newUrl = conn.getHeaderField("Location");
				
				// get the cookie if need, for login
				String cookies = conn.getHeaderField("Set-Cookie");

				// open the new connnection again
				obj =  new URL(newUrl);
				conn = (HttpURLConnection) obj.openConnection();
				conn.setRequestProperty("Cookie", cookies);
				conn.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
				conn.addRequestProperty("User-Agent", uaString);
				conn.addRequestProperty("Referer", "google.com");
			}

			// if file doesn't exists, then create it
			if (!outputFile.exists()) {
				outputFile.createNewFile();
			}
			// can we write this file
			if(!outputFile.canWrite()){
				notifyError("Could not save page", "Cannot write to file - "+outputFile.getAbsolutePath());
				System.out.println("Cannot write to file - "+outputFile.getAbsolutePath());
				return;
			}
			
			in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
			
			String inputLine;
			StringBuffer strResponse = new StringBuffer();
			// append whole response into single string, save it into a file on storage
			// if its of type html then parse it and get all css and images and javascript files
			// and add them to filesToGrab list
			while ((inputLine = in.readLine()) != null) {
				strResponse.append(inputLine+"\r\n");
			}
			
			notifyProgress("Parsing main HTML file", 100, 5);
			String htmlContent = strResponse.toString();
			htmlContent = GrabUtility.searchForNewFilesToGrab(htmlContent, obj);

			outputFile = new File(outputDir, filename);

			try {
				// clear previous files contents
				fop = new FileOutputStream(outputFile);
				fop.write(htmlContent.getBytes());
				fop.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			failCount = 0;
		} catch (Exception e) {
			
			failCount++;

			if (failCount <= 5) {
				notifyError(null, "Failed to download main HTML file, retrying. Fail count: " + failCount );
				synchronized (this) {try {wait(2500);} catch (InterruptedException ex) {}}
				downloadMainHtmlAndParseLinks(url, outputDir);
			} else {
				notifyError("Could not save page", "Failed to download main HTML file.");
				throw new IOException();
			}

		} finally {
			try {
				if(is != null){
					is.close();
				}
				if(in != null){
					in.close();
				}
				if (fop != null) {
					fop.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	private void getExtraFile(URL obj, File outputDir) throws IOException {
		FileOutputStream fop = null;
		BufferedReader in = null;
		HttpURLConnection conn = null;
		File outputFile = null;
		InputStream is = null;
		try {
			String path = obj.getPath();
			String filename = path.substring(path.lastIndexOf('/')+1);
			if(filename.equals("/") || filename.equals("")){
				return;
			}

			//Output file name
			outputFile = new File(outputDir, filename);

			conn = (HttpURLConnection) obj.openConnection();
			conn.setReadTimeout(5000);
			conn.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
			conn.addRequestProperty("User-Agent", uaString);
			conn.addRequestProperty("Referer", "google.com");

			boolean redirect = false;

			// normally, 3xx is redirect
			int status = conn.getResponseCode();
			if (status != HttpURLConnection.HTTP_OK) {
				if (status == HttpURLConnection.HTTP_MOVED_TEMP
					|| status == HttpURLConnection.HTTP_MOVED_PERM
					|| status == HttpURLConnection.HTTP_SEE_OTHER){
					redirect = true;
				}else{
					
					return;
				}
			}

			if (redirect) {
				// get redirect url from "location" header field
				String newUrl = conn.getHeaderField("Location");

				// get the cookie if need, for login
				String cookies = conn.getHeaderField("Set-Cookie");

				// open the new connnection again
				obj =  new URL(newUrl);
				conn = (HttpURLConnection) obj.openConnection();
				conn.setRequestProperty("Cookie", cookies);
				conn.addRequestProperty("Accept-Language", "en-US,en;q=0.8");
				conn.addRequestProperty("User-Agent", "Mozilla");
				conn.addRequestProperty("Referer", "google.com");

				System.out.println("Redirect to URL : " + newUrl);
			}

			// if file doesn't exists, then create it
			if (!outputFile.exists()) {
				outputFile.createNewFile();
			}
			// can we write this file
			if(!outputFile.canWrite()){
				System.out.println("Cannot write to file - "+outputFile.getAbsolutePath());
				return;
			}
			try {
				fop = new FileOutputStream(outputFile);
				is = conn.getInputStream();
				// clear previous files contents
				byte[] buffer = new byte[1024*16]; // read in batches of 16K
		        int length;
		        while ((length = is.read(buffer)) > 0) {
		            fop.write(buffer, 0, length);
	            }
					fop.flush();
					failCount = 0;					
				} catch (IOException e) {
					failCount++;

					if (failCount <= 5) {
						notifyError(null, "Failed to download: " + outputFile.getName() + ", retrying. Fail count: " + failCount );
						synchronized (this) {try {wait(2500);} catch (InterruptedException ex) {}}
						getExtraFile(obj, outputDir);
					} else {
						notifyError(null, "Failed to download extra file: " + outputFile.getName());
						//handle it properly
					}
				
			}
		failCount = 0;
		} catch (Exception e) {
			failCount++;
			
			if (failCount <= 5) {
				notifyError(null, "Failed to download: " + outputFile.getName() + ", retrying in three seconds. Fail count: " + failCount );
				synchronized (this) {try {wait(2500);} catch (InterruptedException ex) {}}
				getExtraFile(obj, outputDir);
			} else {
				notifyError("Could not save page", "Failed to download extra file: " + outputFile.getName());
				
			}
			
			
		} finally {
			try {
				if(is != null){
					is.close();
				}
				if(in != null){
					in.close();
				}
				if (fop != null) {
					fop.close();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}

/**
 * @author Pramod Khare
 * Contains all the utility methods used in above GrabWebPage class
 */
class GrabUtility{
	// filesToGrab - maintains all the links to files which we are going to grab/download
	public static List<String> filesToGrab = new ArrayList<String>();
	// grabbedFiles - links/urls to files which we have already downloaded
	public static HashSet<String> grabbedFiles = new HashSet<String>();
	
	public static String title;

	public static void addLinkGrabbedFilesList(String url){
		synchronized (grabbedFiles) {
			grabbedFiles.add(url);
		}
	}

	public static String getMovedUrlLocation(String responseHeader){
		//handle HTTP Response
		StringTokenizer stk = new StringTokenizer(responseHeader.toString(), "\n", false);
		//check the new URL from response's location field
		String newUrl = null;
		while(stk.hasMoreTokens()){
			String tmp = stk.nextToken();
			if(tmp.toLowerCase().startsWith("location:") && 
			   tmp.split(" ")[1] != null && !tmp.split(" ")[1].trim().equals("")){
				newUrl = tmp.split(" ")[1];
				break;
			}
		}
		return newUrl;
	}

	public static String searchForNewFilesToGrab(String htmlContent, URL fromHTMLPageUrl){
		//get all links from this webpage and add them to Frontier List i.e. LinksToVisit ArrayList
		Document responseHTMLDoc = null;
		String urlToGrab = null;
		if(!htmlContent.trim().equals("")){
			responseHTMLDoc = Jsoup.parse(htmlContent);
			
			title = responseHTMLDoc.title();
			// GrabUtility.searchNewLinksForCrawling(responseHTMLDoc, url);
			// Get all the links
			System.out.println("All Links - ");
			Elements links = responseHTMLDoc.select("link[href]");
			for(Element link: links){
				urlToGrab = link.attr("href");
				addLinkToFrontier(urlToGrab, fromHTMLPageUrl);
				System.out.println("Actual URL - "+urlToGrab);
				String replacedURL = urlToGrab.substring(urlToGrab.lastIndexOf("/")+1);
				htmlContent = htmlContent.replaceAll(urlToGrab, replacedURL);
				System.out.println("Replaced URL - "+replacedURL);
			}

			System.out.println("All external scripts - ");
			Elements links2 = responseHTMLDoc.select("script[src]");
			for(Element link: links2){
				urlToGrab = link.attr("src");
				addLinkToFrontier(urlToGrab, fromHTMLPageUrl);
				System.out.println("Actual URL - "+urlToGrab);
				String replacedURL = urlToGrab.substring(urlToGrab.lastIndexOf("/")+1);
				htmlContent = htmlContent.replaceAll(urlToGrab, replacedURL);
				System.out.println("Replaced URL - "+replacedURL);
			}

			System.out.println("All images - ");
			Elements links3 = responseHTMLDoc.select("img[src]");
			for(Element link: links3){
				urlToGrab = link.attr("src");
				addLinkToFrontier(urlToGrab, fromHTMLPageUrl);
				System.out.println("Actual URL - "+urlToGrab);
				String replacedURL = urlToGrab.substring(urlToGrab.lastIndexOf("/")+1);
				htmlContent = htmlContent.replaceAll(urlToGrab, replacedURL);
				System.out.println("Replaced URL - "+replacedURL);
			}
		}
		return htmlContent;
	}

	public static void addLinkToFrontier(String link, URL fromHTMLPageUrl) {
		synchronized (filesToGrab) {
			if(link.startsWith("/")){
				// meaning absolute url from root
				System.out.println("Absolute Link - "+getRootUrlString(fromHTMLPageUrl)+link);
				filesToGrab.add(getRootUrlString(fromHTMLPageUrl)+link);
			} else if(link.startsWith("http://") && !filesToGrab.contains(link)){
				System.out.println("Full Doamin Link - "+link);
				URL url;
				try {
					url = new URL(link);
					if(isValidlink(url, fromHTMLPageUrl))	//if link from different domain
						filesToGrab.add(link);
				} catch (MalformedURLException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} else {
				// meaning relative url from current directory
				System.out.println("Relative Link - "+getCurrentFolder(fromHTMLPageUrl)+link);
				filesToGrab.add(getCurrentFolder(fromHTMLPageUrl)+link);
			}
		}
	}

	public static String getCurrentFolder(URL url){
		String port = (url.getPort() == -1)? "" :(":"+String.valueOf(url.getPort()));
		String path = url.getPath();
		String currentFolderPath = path.substring(0, path.lastIndexOf("/") + 1);
		return url.getProtocol() +"://" + url.getHost()+ port + currentFolderPath;
	}

	public static String getRootUrlString(URL url){
		String port = (url.getPort() == -1)? "" :(":"+String.valueOf(url.getPort()));
		return url.getProtocol() +"://" + url.getHost()+ port;
	}

	//links like mailto, .pdf, or any file downloads, are not to be crawled
	public static boolean isValidlink(URL link, URL fromHTMLPageUrl) {
		//if link is from same domain
		if (getRootUrlString(link).equalsIgnoreCase(getRootUrlString(fromHTMLPageUrl))){
			return true;
		} else {
			return false;
		}
	}

	public static boolean isURLAlreadyGrabbed(String url){
		synchronized (grabbedFiles) {
			return grabbedFiles.contains(url);
		}
	}
}
