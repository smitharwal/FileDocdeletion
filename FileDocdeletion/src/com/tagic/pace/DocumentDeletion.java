package com.tagic.pace;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Date;
import java.util.ResourceBundle;

/**
 * @author Sumit, Chanda
 * @since 19-Dec-23
 *
 * Utility for document deletion form file system basis omni flag and update in table once document deleted 
 */

public class DocumentDeletion {
	/**
	 * Document deletion from file system and empty directory deletion
	 * @author Sumit
	 * @throws IOException
	 * 
	 */
	
	private void nbdocumentDeletion() throws IOException {//throws IOException{
		ResourceBundle rb = null;//props file
		FileWriter logWriter = null;//to write logs
		//db operations
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		
		int dbRecordCount = 0;//count number of result set records iterated
		int docDeletionCount = 0, docDeletionFailCount = 0;//doc deletion counts
		int dirDeletionCount = 0, dirDeletionFailCount = 0;//directory deletion count

		try {
			//load properties file
			rb = ResourceBundle.getBundle("resources/config");

			if (rb != null) {
				//			if(ip != null) {
				String logFilepath = rb.getString("log.NB.filepath");

				String jdbcDriver = rb.getString("jdbc.driver");
				String jdbcUrl = rb.getString("jdbc.url");
				String jdbcUsername = rb.getString("jdbc.username");
				String jdbcPassword = rb.getString("jdbc.password");//base64 encrypted password
				String jdbcMaxRows = rb.getString("jdbc.NB.maxRows");

				String omniDirectoryPath = rb.getString("omni.directorypath");
				String sharedDirectoryPath = rb.getString("pace.directorypath");

				// To decrypt the password
				Decoder decoder = Base64.getDecoder();
				byte[] decode = decoder.decode(jdbcPassword.getBytes());
				String decodedPassword = new String(decode);
				
				//start doc deletion
				logWriter = new FileWriter(logFilepath, true);

				logWriter.write("\n\n\n**********************************************\tAPPLICATION IS STARTED "+ getLogTime('Z') + "***************************************************\n\n");
				try {
					//driver
					try {
						Class.forName(jdbcDriver);
					} catch (ClassNotFoundException e) {
						logWriter.write(getLogTime('E') + "Driver not found: " + e.toString() + "\n\n");
					}
					conn = DriverManager.getConnection(jdbcUrl, jdbcUsername, decodedPassword);
					stmt = conn.createStatement();

					//#$&%(\!\'.,-+-_

					//get details of files to be deleted
					//[120224] Chanda: submit_date kept as 2022-11-01 since archival is in progress for before cases and will not be available in main trx tables 
					rs = stmt.executeQuery("Select TOP " + jdbcMaxRows + " tx.file_name as file_name, tx.case_no, tx.id as omni_id, nb.id as nb_doc_id, tx.Submit_date as omni_created_on "
							+ "from tx_Omni_Upload AS tx with(nolock) JOIN nb_doc_name_file_system AS nb with(nolock) ON tx.case_no = nb.case_no and tx.file_name=nb.name "
							+ "where tx.Omni_Flag='G' and tx.Submit_date>'2022-11-01' and nb.is_delete IS NULL and tx.file_name is not null order by tx.id ");

					//loop over result set to delete docs from all applicable locations
					String caseNoToLog = null;
					while (rs.next()) {
						dbRecordCount++;

						int omniId = rs.getInt("omni_id"); // To get omni table ID of filename
						int nbDocId = rs.getInt("nb_doc_id");

						Date omniCreatedDate = rs.getDate("omni_created_on");

						//added split to handle soft delete cases
						String caseNo = rs.getString("case_no") != null ? rs.getString("case_no").split("_")[0] : rs.getString("case_no");
						if(!caseNo.equals(caseNoToLog)) {
							caseNoToLog = caseNo;//assign result set case no to logging case no variable
							logWriter.write(getLogTime('I') + "CASE NO = "+ caseNoToLog +"\n");
						}
						String fileName = rs.getString("file_name");

						// form complete file path for doc deletion 
						String omniFilePath = omniDirectoryPath.concat("/").concat(fileName); //for omni replicated
						String nbFileDirectory = sharedDirectoryPath.concat("/").concat(caseNo);
						String nbFilePath = nbFileDirectory.concat("/").concat(fileName); //for pace uploaded

						//same file path for Omni to read docs changes went live on this date
						SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd");
						Date goliveDate = simpleDateFormat.parse("2023-08-03");

						boolean isDeleted = false;//initialize to false;
						isDeleted = Files.deleteIfExists(Paths.get(nbFilePath));

						if(isDeleted) {
							docDeletionCount++;
							logWriter.write(getLogTime('I') + "File deleted successfully; NB Doc ID = " + nbDocId + ", file path = " + nbFilePath + ".\n");
						} else {
							docDeletionFailCount++;
							logWriter.write(getLogTime('I') + "File does not exist; NB Doc ID = " + nbDocId + ", file path = "+ nbFilePath + ".\n");
						}

						//check if record created before go live date if true then, attempt doc deletion since path would be different
						if(omniCreatedDate.before(goliveDate)) {
							isDeleted = false;
							isDeleted = Files.deleteIfExists(Paths.get(omniFilePath));

							if(isDeleted) {
								docDeletionCount++;
								logWriter.write(getLogTime('I') + "File deleted successfully; Omni ID = " + omniId + ", file path = " + omniFilePath + ".\n");
							} else {
								docDeletionFailCount++;
								logWriter.write(getLogTime('I') + "File does not exist; Omni ID = " + omniId + ", file path = "+ omniFilePath + ".\n");
							}
						}

						if(isDeleted)//successfully deleted
							updateNBFlagValue(conn, logWriter, nbDocId, true);
						else//deletion was unsuccessful
							updateNBFlagValue(conn, logWriter, nbDocId, false);

						//empty directory deletion
						File tempFolder = new File(nbFileDirectory);
						if(tempFolder.isDirectory()) {//check if file path is existent and is a directory
							if(tempFolder.list().length == 0) {// check if directory is empty
								isDeleted = false;
								isDeleted = Files.deleteIfExists(Paths.get(nbFileDirectory));

								if(isDeleted) {
									dirDeletionCount++;
									logWriter.write(getLogTime('I') + "Empty directory deleted successfully; directory path = " + nbFileDirectory + ".\n");
								} else {
									dirDeletionFailCount++;
									logWriter.write(getLogTime('I') + "Cannot remove directory; directory path = "+ nbFileDirectory + ".\n");
								}
							} else {
								logWriter.write(getLogTime('I') + "Directory is not empty; directory path = "+ nbFileDirectory + ".\n");
							}
						} else {
							logWriter.write(getLogTime('I') + "Directory does not exist; directory path = "+ nbFileDirectory + ".\n");
						}
					}

					if(dbRecordCount > 0) {//number of records iterated in result set is not zero
						logWriter.write(getLogTime('I') + "TOTAL RECORD COUNT = " + dbRecordCount 
								+ ", TOTAL DOCS DELETED = " + docDeletionCount + ", TOTAL DOCS DELETION FAILED = " + docDeletionFailCount
								+ ", TOTAL DIRECTORIES DELETED = " + dirDeletionCount + ", TOTAL DIRECTORIES DELETION FAILED = " + dirDeletionFailCount + "\n");
					} else {
						logWriter.write(getLogTime('I') + "No Records Found.\n");
					}

				} catch (SQLException e) {
					logWriter.write(getLogTime('E') + "SQLException occured while trying to fetch data. Error: " + e.toString() + "\n\n");
				} catch (Exception e) {
					logWriter.write(getLogTime('E') + "Exception occured. Error: " + e.toString() + "\n\n");
//					System.out.println();
					e.printStackTrace();
				} finally {
					try {
						if (rs != null)
							rs.close();
						if (stmt != null)
							stmt.close();
						if (conn != null)
							conn.close();
					} catch (SQLException e) {
						logWriter.write(getLogTime('E') + "SQLException occured while trying to close ResultSet or Statement or Connection. Error: "+ e.toString() + "\n\n");
					}
				}
			} else {
				//unable to load properties file
				//do nothing
				System.out.println("else block, do nothing");
			}
		} finally {
			if (logWriter != null)
				logWriter.close();
		}
	}
	
	private void gcocrdocumentDeletion() throws IOException {//throws IOException{
		ResourceBundle rb = null;//props file
		FileWriter logWriter = null;//to write logs
		//db operations
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		
		int dbRecordCount = 0;//count number of result set records iterated
		int docDeletionCount = 0, docDeletionFailCount = 0;//doc deletion counts
		int dirDeletionCount = 0, dirDeletionFailCount = 0;//directory deletion count
		
		try {
			//load properties file
			rb = ResourceBundle.getBundle("resources/config");

			if (rb != null) {
				//			if(ip != null) {
				String logFilepath = rb.getString("log.OCR.filepath");

				String jdbcDriver = rb.getString("jdbc.driver");
				String jdbcUrl = rb.getString("jdbc.url");
				String jdbcUsername = rb.getString("jdbc.username");
				String jdbcPassword = rb.getString("jdbc.password");//base64 encrypted password
				String jdbcMaxRows = rb.getString("jdbc.OCR.maxRows");

				String DirectoryPath = rb.getString("ocr.directorypath");

				// To decrypt the password
				Decoder decoder = Base64.getDecoder();
				byte[] decode = decoder.decode(jdbcPassword.getBytes());
				String decodedPassword = new String(decode);
				
				//start doc deletion
				logWriter = new FileWriter(logFilepath, true);

				logWriter.write("\n\n\n**********************************************\tAPPLICATION IS STARTED "+ getLogTime('Z') + "***************************************************\n\n");
				try {
					//driver
					try {
						Class.forName(jdbcDriver);
					} catch (ClassNotFoundException e) {
						logWriter.write(getLogTime('E') + "Driver not found: " + e.toString() + "\n\n");
					}
					conn = DriverManager.getConnection(jdbcUrl, jdbcUsername, decodedPassword);
					stmt = conn.createStatement();

					//#$&%(\!\'.,-+-_

					//get details of files to be deleted
					rs = stmt.executeQuery("Select TOP " + jdbcMaxRows + " tx.doc_name as file_name, tx.ocr_case_no , tx.id as omni_id, ocr.id as ocr_doc_id, tx.created_on as omni_created_on " 
							+ "from tx_omni_ocr_document AS tx with(nolock) JOIN nb_ocr_file_details AS ocr with(nolock) ON tx.ocr_case_no = ocr.case_no and tx.doc_name=ocr.name "
							+ "where tx.Omni_Flag='Y'  and ocr.is_delete IS NULL and tx.doc_name is not null order by tx.id ");

					//loop over result set to delete docs from all applicable locations
					//String caseNoToLog = null;
					while (rs.next()) {
						dbRecordCount++;

						int ocrDocId = rs.getInt("ocr_doc_id");

						//added split to handle soft delete cases
						String caseNo = rs.getString("ocr_case_no");					
						String fileName = rs.getString("file_name");

						// form complete file path for doc deletion 
						String ocrFileDirectory = DirectoryPath.concat("/").concat(caseNo);
						String ocrFilePath = ocrFileDirectory.concat("/").concat(fileName); //for pace uploaded

						boolean isDeleted = false;//initialize to false;
						isDeleted = Files.deleteIfExists(Paths.get(ocrFilePath));

						if(isDeleted) {
							docDeletionCount++;
							logWriter.write(getLogTime('I') + "File deleted successfully; NB Doc ID = " + ocrDocId + ", file path = " + ocrFilePath + ".\n");
						} else {
							docDeletionFailCount++;
							logWriter.write(getLogTime('I') + "File does not exist; NB Doc ID = " + ocrDocId + ", file path = "+ ocrFilePath + ".\n");
						}

						
						if(isDeleted)//successfully deleted
							updateOCRFlagValue(conn, logWriter, ocrDocId, true);
						else//deletion was unsuccessful
							updateOCRFlagValue(conn, logWriter, ocrDocId, false);

						//empty directory deletion
						File tempFolder = new File(ocrFileDirectory);
						if(tempFolder.isDirectory()) {//check if file path is existent and is a directory
							if(tempFolder.list().length == 0) {// check if directory is empty
								isDeleted = false;
								isDeleted = Files.deleteIfExists(Paths.get(ocrFileDirectory));

								if(isDeleted) {
									dirDeletionCount++;
									logWriter.write(getLogTime('I') + "Empty directory deleted successfully; directory path = " + ocrFileDirectory + ".\n");
								} else {
									dirDeletionFailCount++;
									logWriter.write(getLogTime('I') + "Cannot remove directory; directory path = "+ ocrFileDirectory + ".\n");
								}
							} else {
								logWriter.write(getLogTime('I') + "Directory is not empty; directory path = "+ ocrFileDirectory + ".\n");
							}
						} else {
							logWriter.write(getLogTime('I') + "Directory does not exist; directory path = "+ ocrFileDirectory + ".\n");
						}
					}

					if(dbRecordCount > 0) {//number of records iterated in result set is not zero
						logWriter.write(getLogTime('I') + "TOTAL RECORD COUNT = " + dbRecordCount 
								+ ", TOTAL DOCS DELETED = " + docDeletionCount + ", TOTAL DOCS DELETION FAILED = " + docDeletionFailCount
								+ ", TOTAL DIRECTORIES DELETED = " + dirDeletionCount + ", TOTAL DIRECTORIES DELETION FAILED = " + dirDeletionFailCount + "\n");
					} else {
						logWriter.write(getLogTime('I') + "No Records Found.\n");
					}

				} catch (SQLException e) {
					logWriter.write(getLogTime('E') + "SQLException occured while trying to fetch data. Error: " + e.toString() + "\n\n");
				} catch (Exception e) {
					logWriter.write(getLogTime('E') + "Exception occured. Error: " + e.toString() + "\n\n");
//					System.out.println();
					e.printStackTrace();
				} finally {
					try {
						if (rs != null)
							rs.close();
						if (stmt != null)
							stmt.close();
						if (conn != null)
							conn.close();
					} catch (SQLException e) {
						logWriter.write(getLogTime('E') + "SQLException occured while trying to close ResultSet or Statement or Connection. Error: "+ e.toString() + "\n\n");
					}
				}
			} else {
				//unable to load properties file
				//do nothing
				System.out.println("else block, do nothing");
			}
		} finally {
			if (logWriter != null)
				logWriter.close();
		}
	}
	
	private void nbhdocumentDeletion() throws IOException {//throws IOException{
		ResourceBundle rb = null;//props file
		FileWriter logWriter = null;//to write logs
		//db operations
		Connection conn = null;
		Statement stmt = null;
		ResultSet rs = null;
		
		int dbRecordCount = 0;//count number of result set records iterated
		int docDeletionCount = 0, docDeletionFailCount = 0;//doc deletion counts
		int dirDeletionCount = 0, dirDeletionFailCount = 0;//directory deletion count
		
		try {
			//load properties file
			rb = ResourceBundle.getBundle("resources/config");

			if (rb != null) {
				//			if(ip != null) {
				String logFilepath = rb.getString("log.NBH.filepath");

				String jdbcDriver = rb.getString("jdbc.driver");
				String jdbcUrl = rb.getString("jdbc.url");
				String jdbcUsername = rb.getString("jdbc.username");
				String jdbcPassword = rb.getString("jdbc.password");//base64 encrypted password
				String jdbcMaxRows = rb.getString("jdbc.NBH.maxRows");

				String DirectoryPath = rb.getString("nbh.directorypath");

				// To decrypt the password
				Decoder decoder = Base64.getDecoder();
				byte[] decode = decoder.decode(jdbcPassword.getBytes());
				String decodedPassword = new String(decode);
				
				//start doc deletion
				logWriter = new FileWriter(logFilepath, true);

				logWriter.write("\n\n\n**********************************************\tAPPLICATION IS STARTED "+ getLogTime('Z') + "***************************************************\n\n");
				try {
					//driver
					try {
						Class.forName(jdbcDriver);
					} catch (ClassNotFoundException e) {
						logWriter.write(getLogTime('E') + "Driver not found: " + e.toString() + "\n\n");
					}
					conn = DriverManager.getConnection(jdbcUrl, jdbcUsername, decodedPassword);
					stmt = conn.createStatement();

					//#$&%(\!\'.,-+-_

					//get details of files to be deleted
					rs = stmt.executeQuery("Select TOP " + jdbcMaxRows + " tx.FILE_NAME as file_name, tx.SOURCE_PROPOSAL_NO as case_no , tx.nbh_odd_id as omni_id, nbh.nbh_cdd_id as nbh_doc_id, tx.created_on as omni_created_on " 
							+ "from NBH_OMNI_DOCUMENT_DETAILS AS tx with(nolock) JOIN NBH_CASE_DOCUMENT_DETAILS AS nbh with(nolock) ON tx.SOURCE_PROPOSAL_NO =(select nbh1.source_proposal_number from NBH_UW_CASE_DETAILS nbh1 with(nolock) where nbh.nbh_ucd_id=nbh1.nbh_ucd_id) " 
							+ "and tx.FILE_NAME=nbh.doc_name where tx.Omni_Flag='Y' and tx.file_name is not null order by tx.nbh_odd_id");

					//loop over result set to delete docs from all applicable locations
					while (rs.next()) {
						dbRecordCount++;

						int nbhDocId = rs.getInt("nbh_doc_id");

						//added split to handle soft delete cases
						String caseNo = rs.getString("case_no");
						String fileName = rs.getString("file_name");

						// form complete file path for doc deletion 
						String nbhFileDirectory = DirectoryPath.concat("/").concat(caseNo);
						String nbhFilePath = nbhFileDirectory.concat("/").concat(fileName); //for pace uploaded

						

						boolean isDeleted = false;//initialize to false;
						isDeleted = Files.deleteIfExists(Paths.get(nbhFilePath));

						if(isDeleted) {
							docDeletionCount++;
							logWriter.write(getLogTime('I') + "File deleted successfully; NB Doc ID = " + nbhDocId + ", file path = " + nbhFilePath + ".\n");
						} else {
							docDeletionFailCount++;
							logWriter.write(getLogTime('I') + "File does not exist; NB Doc ID = " + nbhDocId + ", file path = "+ nbhFilePath + ".\n");
						}


						if(isDeleted)//successfully deleted
							updateNBHFlagValue(conn, logWriter, nbhDocId, true);
						else//deletion was unsuccessful
							updateNBHFlagValue(conn, logWriter, nbhDocId, false);

						//empty directory deletion
						File tempFolder = new File(nbhFileDirectory);
						if(tempFolder.isDirectory()) {//check if file path is existent and is a directory
							if(tempFolder.list().length == 0) {// check if directory is empty
								isDeleted = false;
								isDeleted = Files.deleteIfExists(Paths.get(nbhFileDirectory));

								if(isDeleted) {
									dirDeletionCount++;
									logWriter.write(getLogTime('I') + "Empty directory deleted successfully; directory path = " + nbhFileDirectory + ".\n");
								} else {
									dirDeletionFailCount++;
									logWriter.write(getLogTime('I') + "Cannot remove directory; directory path = "+ nbhFileDirectory + ".\n");
								}
							} else {
								logWriter.write(getLogTime('I') + "Directory is not empty; directory path = "+ nbhFileDirectory + ".\n");
							}
						} else {
							logWriter.write(getLogTime('I') + "Directory does not exist; directory path = "+ nbhFileDirectory + ".\n");
						}
					}

					if(dbRecordCount > 0) {//number of records iterated in result set is not zero
						logWriter.write(getLogTime('I') + "TOTAL RECORD COUNT = " + dbRecordCount 
								+ ", TOTAL DOCS DELETED = " + docDeletionCount + ", TOTAL DOCS DELETION FAILED = " + docDeletionFailCount
								+ ", TOTAL DIRECTORIES DELETED = " + dirDeletionCount + ", TOTAL DIRECTORIES DELETION FAILED = " + dirDeletionFailCount + "\n");
					} else {
						logWriter.write(getLogTime('I') + "No Records Found.\n");
					}

				} catch (SQLException e) {
					logWriter.write(getLogTime('E') + "SQLException occured while trying to fetch data. Error: " + e.toString() + "\n\n");
				} catch (Exception e) {
					logWriter.write(getLogTime('E') + "Exception occured. Error: " + e.toString() + "\n\n");
//					System.out.println();
					e.printStackTrace();
				} finally {
					try {
						if (rs != null)
							rs.close();
						if (stmt != null)
							stmt.close();
						if (conn != null)
							conn.close();
					} catch (SQLException e) {
						logWriter.write(getLogTime('E') + "SQLException occured while trying to close ResultSet or Statement or Connection. Error: "+ e.toString() + "\n\n");
					}
				}
			} else {
				//unable to load properties file
				//do nothing
				System.out.println("else block, do nothing");
			}
		} finally {
			if (logWriter != null)
				logWriter.close();
		}
	}
	/**
	 * Get the log timestamp along with log type 
	 * @param logType
	 * @return
	 */
	private static String getLogTime(Character logType) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy HH:mm:ss:SSS");
		String returnString;
		if(logType.equals('E'))
			returnString = ("[".concat(dateFormat.format(new Date())).concat("] E\t"));
		else if(logType.equals('I'))
			returnString = ("[".concat(dateFormat.format(new Date())).concat("] I\t"));
		else
			returnString = ("[".concat(dateFormat.format(new Date())).concat("] \t"));

		return returnString;
	}

	/**
	 * Update is_delete flag for doc that is deleted
	 * @param conn
	 * @param logWriter
	 * @param FileSystemId
	 * @throws IOException 
	 * @throws Exception
	 */
	private static void updateNBFlagValue(Connection conn, FileWriter logWriter, int fileSystemId, boolean isSuccess) throws IOException {
		Statement stmt  = null;
		try {
			stmt = conn.createStatement();
			stmt.executeUpdate("update nb_doc_name_file_system with (rowlock) set is_delete='"+ (isSuccess ? "Y":"Z") +"' where id=" + fileSystemId);
			//			logWriter.write(getLogTime('I') + "Update is_delete flag for id = " + fileSystemId + ".\n");
		} catch (SQLException e) {
			logWriter.write(getLogTime('E') + "SQLException occured while trying to update flag. Error: " + e.getMessage() + "\n\n");
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				}  catch (SQLException e) {
					logWriter.write(getLogTime('E') + "Exception occured while trying to close ResultSet or Statement in update flag. Error: "+ e.getMessage() + "\n\n");
				}
			}
		}
	}
	
	private static void updateOCRFlagValue(Connection conn, FileWriter logWriter, int fileSystemId, boolean isSuccess) throws IOException {
		Statement stmt  = null;
		try {
			stmt = conn.createStatement();
			stmt.executeUpdate("update nb_ocr_file_details with (rowlock) set is_delete='"+ (isSuccess ? "Y":"Z") +"' where id=" + fileSystemId);
			//			logWriter.write(getLogTime('I') + "Update is_delete flag for id = " + fileSystemId + ".\n");
		} catch (SQLException e) {
			logWriter.write(getLogTime('E') + "SQLException occured while trying to update flag. Error: " + e.getMessage() + "\n\n");
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				}  catch (SQLException e) {
					logWriter.write(getLogTime('E') + "Exception occured while trying to close ResultSet or Statement in update flag. Error: "+ e.getMessage() + "\n\n");
				}
			}
		}
	}
	
	private static void updateNBHFlagValue(Connection conn, FileWriter logWriter, int fileSystemId, boolean isSuccess) throws IOException {
		Statement stmt  = null;
		try {
			stmt = conn.createStatement();
			stmt.executeUpdate("update NBH_CASE_DOCUMENT_DETAILS with (rowlock) set is_delete='"+ (isSuccess ? "Y":"Z") +"' where id=" + fileSystemId);
			//			logWriter.write(getLogTime('I') + "Update is_delete flag for id = " + fileSystemId + ".\n");
		} catch (SQLException e) {
			logWriter.write(getLogTime('E') + "SQLException occured while trying to update flag. Error: " + e.getMessage() + "\n\n");
		} finally {
			if (stmt != null) {
				try {
					stmt.close();
				}  catch (SQLException e) {
					logWriter.write(getLogTime('E') + "Exception occured while trying to close ResultSet or Statement in update flag. Error: "+ e.getMessage() + "\n\n");
				}
			}
		}
	}

	public static void main(String[] args) {

		DocumentDeletion obj = new DocumentDeletion();

		try {
			obj.nbdocumentDeletion();
			obj.gcocrdocumentDeletion();
			obj.nbhdocumentDeletion();
		} catch (Exception e) {
			System.out.println(e.getMessage());
			//do nothing here since all exception handling done inside function
		} finally {
			obj = null;
		}
	}
}
