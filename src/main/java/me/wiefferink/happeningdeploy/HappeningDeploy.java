package me.wiefferink.happeningdeploy;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class HappeningDeploy {

	private File self;
	private Set<String> filteredFiles;

	public HappeningDeploy(String[] args) {
		self = new File(HappeningDeploy.class.getProtectionDomain().getCodeSource().getLocation().getPath());

		String deployKey = null;
		String url = null;
		String directory = null;
		File directoryFile;
		// Check arguments
		for(String arg : args) {
			if(deployKey == null) {
				deployKey = arg;
			} else if(url == null) {
				url = arg;
			} else if(directory == null){
				directory = arg;
			} else {
				directory += arg;
			}
		}
		// Determine fallback parameters
		if(directory == null) {
			// Set to directory we are running in
			directory = System.getProperty("user.dir");
		}
		directoryFile = new File(directory);
		if(!directoryFile.exists()) {
			error("Specified directory does not exist: "+directoryFile.getAbsolutePath());
			return;
		}

		if(deployKey == null) {
			File deploykeyFile = new File(directoryFile.getAbsolutePath() + File.separator + ".deploykey");
			try (BufferedReader reader = new BufferedReader(new FileReader(deploykeyFile))) {
				deployKey = reader.readLine();
			} catch (IOException ignore) {}
		}
		if(deployKey == null || deployKey.isEmpty()) {
			error("No deploy key specified, specify as first argument or put it in the .deploykey file");
			return;
		}

		if(url == null) {
			url = "http://happening.im/plugin/";
		}

		// Get content of .deployignore
		filteredFiles = new HashSet<>();
		File deployIgnoreFile = new File(directoryFile.getAbsolutePath() + File.separator + ".deployignore");
		try (BufferedReader reader = new BufferedReader(new FileReader(deployIgnoreFile))) {
			String line = reader.readLine();
			while(line != null) {
				filteredFiles.add(line);
				line = reader.readLine();
			}
		} catch (IOException ignore) {
		}

		upload(url, deployKey, directoryFile);
	}

	/**
	 * Upload the plugin to Happening
	 * @param url The url to upload to (the actual plugin target, not the base url)
	 * @param deployKey The key to use for deploying
	 * @param directory The directory to upload
	 */
	public void upload(String url, String deployKey, File directory) {
		progress("Uploading '"+directory.getName()+"' to Happening");
		progress("  Creating zip...");
		File zip = zip(directory.getAbsolutePath()+File.separator+"_upload.zip", directory.getAbsolutePath());
		if(zip == null) {
			return;
		}
		progress("  Uploading...");
		CloseableHttpClient httpclient = HttpClients.createDefault();
		HttpPost httppost = new HttpPost(url+deployKey);
		FileEntity entity = new FileEntity(zip, ContentType.APPLICATION_OCTET_STREAM);
		httppost.setEntity(entity);
		try {
			CloseableHttpResponse response = httpclient.execute(httppost);
			HttpEntity resEntity = response.getEntity();
			String result = null;
			if (resEntity != null) {
				result = EntityUtils.toString(resEntity);
				result = "  "+result.replaceAll("(^[\r\n\\s]*)|([\r\n\\s]*$)", "").replaceAll("(\r\n|[\n\r])", "$1  ");
				EntityUtils.consume(resEntity);
			}
			if(!"HTTP/1.1 200 OK".equalsIgnoreCase(response.getStatusLine().toString())) {
				if(result == null) {
					error("  Uploading failed: " + response.getStatusLine().toString());
				}
				error(result);
			} else {
				progress(result);
			}
			response.close();
		} catch (IOException e) {
			error("  HTTP error: "+e.getMessage());
			e.printStackTrace(System.err);
		}
		if(!zip.delete()) {
			error("  Could not delete temprary zip file: "+zip.getAbsolutePath());
		}
	}

	/**
	 * Zip a folder
	 * @param zipTarget The target file
	 * @param toZipFolder The folder/file that should be zipped
	 * @return The target file
	 */
	public File zip(String zipTarget, String toZipFolder) {
		// Check input
		File toZipFolderFile = new File(toZipFolder);
		if(!toZipFolderFile.exists()) {
			error("  To zip folder does not exist: "+toZipFolderFile.getAbsolutePath());
			return null;
		}
		File zipTargetFile = new File(zipTarget);
		if(zipTargetFile.exists()) {
			if (!zipTargetFile.delete()) {
				error("  Could not delete old temporary zip file: " + zipTargetFile.getAbsolutePath());
				return null;
			}
		}
		Set<File> files = getFilesRecursive(toZipFolderFile);
		// Create leading directories
		zipTargetFile.getParentFile().mkdirs();
		// Write to zip
		byte[] buffer = new byte[1024];
		try (
				ZipOutputStream output = new ZipOutputStream(new FileOutputStream(zipTarget))
			) {
			for (File file : files) {
				//System.out.println("    File added: " + file.getAbsolutePath());
				ZipEntry zipEntry = new ZipEntry(file.getAbsolutePath().replace(toZipFolderFile.getAbsolutePath(), "").substring(1));
				output.putNextEntry(zipEntry);
				FileInputStream input = new FileInputStream(file);
				int length;
				while ((length = input.read(buffer)) > 0) {
					output.write(buffer, 0, length);
				}
				input.close();
			}
			output.closeEntry(); // Close last file entry
		} catch (IOException e) {
			e.printStackTrace();
		}
		return zipTargetFile;
	}

	/**
	 * Get all files recursively if a directory is given, otherwise the file itself
	 *
	 * @param start The file to check
	 * @return A list of all files in the directory
	 */
	public Set<File> getFilesRecursive(File start) {
		return getFilesRecursive(start, start, new HashSet<File>());
	}

	/**
	 * Get all files recursively if a directory is given, otherwise the file itself
	 * @param node The file to check
	 * @param current The current list of files
	 * @return A list of all files in the directory
	 */
	private Set<File> getFilesRecursive(File node, File root, Set<File> current) {
		if(node.isHidden() || node.equals(self)) {
			return current;
		}

		if (node.isFile()) {
			// Check .deployignore filter
			String path = node.getAbsolutePath().substring(root.getAbsolutePath().length()+1);
			for(String filter : filteredFiles) {
				// Match complete relative path, allow leading directories like *nix zip does
				if(Pattern.matches("^(.*"+ Matcher.quoteReplacement(File.separator)+")?"+filter.replace("*", ".*")+"$", path)) {
					return current;
				}
			}
			current.add(node);
		} else if (node.isDirectory()) {
			File[] files = node.listFiles();
			if(files != null) {
				for (File file : files) {
					getFilesRecursive(file, root, current);
				}
			}
		}
		return current;
	}

	/**
	 * Print error message
	 * @param errorMessage The error message to print
	 */
	private void error(String errorMessage) {
		if(errorMessage != null) {
			System.err.println(errorMessage);
		}
	}

	/**
	 * Print progress message
	 * @param progressMessage The progress message to print
	 */
	private void progress(String progressMessage) {
		if(progressMessage != null) {
			System.out.println(progressMessage);
		}
	}

	/**
	 * Main method
	 * @param args command line arguments
	 */
	public static void main(String[] args) {
		new HappeningDeploy(args);
	}
}
