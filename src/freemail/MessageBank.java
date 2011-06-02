/*
 * MessageBank.java
 * This file is part of Freemail
 * Copyright (C) 2006,2008 Dave Baker
 * Copyright (C) 2008 Alexander Lehmann
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package freemail;

import java.io.IOException;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.TreeMap;
import java.util.SortedMap;
import java.util.Vector;
import java.util.Enumeration;
import java.util.Comparator;
import java.util.Arrays;

public class MessageBank {
	private static final String MESSAGES_DIR = "inbox";
	private static final String NIDFILE = ".nextid";
	private static final String NIDTMPFILE = ".nextid-tmp";

	private final File dir;

	public MessageBank(FreemailAccount account) {
		this.dir = new File(account.getAccountDir(), MESSAGES_DIR);
		
		if (!this.dir.exists()) {
			this.dir.mkdir();
		}
	}
	
	private MessageBank(File d) {
		this.dir = d;
	}
	
	public String getName() {
		return this.dir.getName();
	}
	
	public String getFolderFlagsString() {
		StringBuffer retval = new StringBuffer("(");
		
		if (this.listSubFolders().length > 0) {
			retval.append("\\HasChildren");
		} else {
			retval.append("\\HasNoChildren");
		}
		
		retval.append(")");
		return retval.toString();
	}
	
	public synchronized boolean delete() {
		File[] files = this.dir.listFiles();
		
		for (int i = 0; i < files.length; i++) {
			if (files[i].getName().equals(".")) continue;
			if (files[i].getName().equals(NIDFILE)) continue;
			if (files[i].getName().equals("..")) continue;
			
			// this method should will fail if there are directories
			// here. It should never be called if this is the case.
			if (!files[i].delete()) return false;
		}
		
		// rename it with a dot in front - we need to preserve the UIDs
		File newdir = new File(this.dir.getParent(), "."+this.dir.getName());
		
		return this.dir.renameTo(newdir);
	}
	
	public synchronized MailMessage createMessage() {
		long newid = this.nextId();
		File newfile;
		try {
			do {
				newfile = new File(this.dir, Long.toString(newid));
				newid++;
			} while (!newfile.createNewFile());
		} catch (IOException ioe) {
			newfile = null;
		}
		
		this.writeNextId(newid);
		
		if (newfile != null) {
			MailMessage newmsg = new MailMessage(newfile,0);
			return newmsg;
		}
		
		return null;
	}
	
	public synchronized SortedMap<Integer, MailMessage> listMessages() {
		File[] files = this.dir.listFiles(new MessageFileNameFilter());

		Arrays.sort(files, new UIDComparator());

		TreeMap msgs = new TreeMap();

		int seq=1;
		for (int i = 0; i < files.length; i++) {
			if (files[i].isDirectory()) continue;
			
			MailMessage msg = new MailMessage(files[i],seq++);
			
			msgs.put(new Integer(msg.getUID()), msg);
		}
		
		return msgs;
	}
	
	public synchronized MailMessage[] listMessagesArray() {
		File[] files = this.dir.listFiles(new MessageFileNameFilter());

		Arrays.sort(files, new UIDComparator());
		
		MailMessage[] msgs = new MailMessage[files.length];
		
		for (int i = 0; i < files.length; i++) {
			//if (files[i].getName().startsWith(".")) continue;
			
			MailMessage msg = new MailMessage(files[i],i+1);
			
			msgs[i] = msg;
		}
		
		return msgs;
	}
	
	public MessageBank getSubFolder(String name) {
		if (!name.matches("[\\w\\s_]*")) return null;
		
		File targetdir = new File(this.dir, name);
		if (!targetdir.exists()) {
			return null;
		}
		return new MessageBank(targetdir);
	}
	
	public synchronized MessageBank makeSubFolder(String name) {
		if (!name.matches("[\\w\\s_]*")) return null;
		
		File targetdir = new File(this.dir, name);
		
		// is there a 'deleted' instance of this folder?
		File ghostdir = new File(this.dir, "."+name);
		if (ghostdir.exists()) {
			if (!ghostdir.renameTo(targetdir)) {
				return null;
			}
			return new MessageBank(ghostdir);
		}
		
		if (targetdir.exists()) {
			return null;
		}
		   
		if (targetdir.mkdir()) {
			return new MessageBank(targetdir);
		}
		return null;
	}
	
	public synchronized MessageBank[] listSubFolders() {
		File[] files = this.dir.listFiles();
		Vector subfolders = new Vector();
		
		for (int i = 0; i < files.length; i++) {
			if (files[i].getName().startsWith(".")) continue;
			
			if (files[i].isDirectory()) {
				subfolders.add(files[i]);
			}
		}
		
		MessageBank[] retval = new MessageBank[subfolders.size()];
		
		Enumeration e = subfolders.elements();
		int i = 0;
		while (e.hasMoreElements()) {
			retval[i] = new MessageBank((File)e.nextElement());
			i++;
		}
		return retval;
	}
	
	private synchronized long nextId() {
		File nidfile = new File(this.dir, NIDFILE);
		long retval;
		
		try {
			BufferedReader br = new BufferedReader(new FileReader(nidfile));
			
			retval = Long.parseLong(br.readLine());
		
			br.close();
		} catch (IOException ioe) {
			return 1;
		} catch (NumberFormatException nfe) {
			return 1;
		}
		
		return retval;
	}
	
	private synchronized void writeNextId(long newid) {
		// write the new ID to a temporary file
		File nidfile = new File(this.dir, NIDTMPFILE);
		try {
			PrintStream ps = new PrintStream(new FileOutputStream(nidfile));
			ps.print(newid);
			ps.flush();
			ps.close();
			
			// make sure the old nextid file doesn't contain a
			// value greater than our one
			if (this.nextId() <= newid) {
				File main_nid_file = new File(this.dir, NIDFILE);
				main_nid_file.delete();
				nidfile.renameTo(main_nid_file);
			}
		} catch (IOException ioe) {
			// how to handle this?
		}
	}
	
	private static class MessageFileNameFilter implements FilenameFilter {
		public boolean accept(File dir, String name) {
			if (name.startsWith(".")) return false;
			if (!name.matches("[0-9]+(,.*)?")) return false;
			return true;
		}
	}
	
	// compare to filenames by number leading up to ","
	private static class UIDComparator implements Comparator {
		public final int compare ( Object a, Object b ) {
			int ia=Integer.parseInt(((File)a).getName().split(",",2)[0]);
			int ib=Integer.parseInt(((File)b).getName().split(",",2)[0]);

			return( ia-ib );
		}
	}


}
