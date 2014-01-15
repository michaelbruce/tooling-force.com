/*
 * Copyright (c) 2014 Andrey Gavrikov.
 * this file is part of tooling-force.com application
 * https://github.com/neowit/tooling-force.com
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.neowit.apex

import com.neowit.utils.{Logging, Config}
import com.sforce.soap.partner.PartnerConnection
import com.sforce.soap.metadata._

import scala.concurrent._
import scala.Some
import java.io.File

/**
 * manages local data store related to specific project
 */
object Session {
    def apply(appConfig: Config) = new Session(appConfig)
}

/**
 * Session has following responsibilities
 * 1. Maintains/stores persistent connection and can resue connection
 * 2. Maintains cache of some important information about project metadata (Pages, Classes, etc)
 *
 * @param config - main application config
 */
class Session(config: Config) extends Logging {

    private val sessionProperties = config.lastSessionProps
    private var connectionPartner:Option[PartnerConnection] = None
    private var connectionMetadata:Option[MetadataConnection] = None

    def getConfig = config


    def storeSessionData() {
        config.storeSessionProps()
    }
    def getSavedConnectionData = {
        (sessionProperties.getPropertyOption("sessionId"), sessionProperties.getPropertyOption("serviceEndpoint"))
    }
    def storeConnectionData(connectionConfig: com.sforce.ws.ConnectorConfig) {
        sessionProperties.setProperty("sessionId", connectionConfig.getSessionId)
        sessionProperties.setProperty("serviceEndpoint", connectionConfig.getServiceEndpoint)
        storeSessionData()
    }
    def setData(key: String, data: Map[String, String]) = {
        sessionProperties.setJsonData(key, data)
    }
    def getData(key: String): Map[String, String] = {
        sessionProperties.getJsonData(key)
    }

    /**
     * return relative path inside project folder
     * this path is used as key in session
     * @param file - resource under project folder
     * @return - string, looks like: unpackaged/pages/Hello.page
     */
    def getRelativePath(file: File): String = {
        val projectPath = config.projectDir.getAbsolutePath + File.separator
        val res = file.getAbsolutePath.substring(projectPath.length)
        res
    }
    /**
     * return relative path inside project folder
     * this path is used as key in session
     * @param file - resource under project folder
     * @return - string, looks like: unpackaged/pages/Hello.page
     */
    def getKeyByFile(file: File): String = {
        val relativePath = getRelativePath(file)
        getKeyByRelativeFilePath(relativePath)
    }

    def getKeyByRelativeFilePath(filePath: String): String = {
        val relPath = if (filePath.startsWith("src/"))
            filePath.replaceFirst("src/", "unpackaged/")
        else
            filePath
        if (relPath.endsWith("-meta.xml"))
            relPath.substring(0, relPath.length - "-meta.xml".length)
        else
            relPath
    }
    def isModified(file: File): Boolean = {
        val fileData = getData(getKeyByFile(file))
        fileData.get(MetadataType.LOCAL_MILLS) match {
            case Some(x) => file.lastModified() > x.toLong
            case None => true
        }

    }

    private def getPartnerConnection: PartnerConnection = {
        val conn = connectionPartner match {
          case Some(connection) => connection
          case None =>
              //check if we have previously established session id
              getSavedConnectionData match {
                  case (Some(sessionId), Some(serviceEndpoint)) =>
                      //use cached data
                      Connection.getPartnerConnection(config, sessionId, serviceEndpoint)
                  case _ =>
                      //login explicitly
                      val _conn = Connection.createPartnerConnection(config)
                      storeConnectionData(_conn.getConfig)
                      _conn
              }

        }
        connectionPartner = Some(conn)

        conn
    }
    private def getMetadataConnection: MetadataConnection = {
        val conn = connectionMetadata match {
            case Some(connection) => connection
            case None => Connection.getMetadataConnection(config, getPartnerConnection)
        }
        connectionMetadata = Some(conn)
        conn
    }

    def withRetry(codeBlock: => Any) = {
        try {
            codeBlock
        } catch {
            case ex:com.sforce.ws.SoapFaultException if "INVALID_SESSION_ID" == ex.getFaultCode.getLocalPart =>
                logger.debug("Session is invalid or has expired. Will run the process again with brand new connection. ")
                logger.trace(ex)
                reset()
                //run once again
                codeBlock
            case ex:Throwable =>
                throw ex
        }
    }
    def reset() {
        sessionProperties.remove("sessionId")
        sessionProperties.remove("serviceEndpoint")
        storeSessionData()
        connectionPartner = None
    }

    def getServerTimestamp = {
        withRetry {
            getPartnerConnection.getServerTimestamp
        }.asInstanceOf[com.sforce.soap.partner.GetServerTimestampResult]
    }

    def retrieve(retrieveRequest: RetrieveRequest ):RetrieveResult = {
        val conn = getMetadataConnection
        val retrieveResult = withRetry {
            val asyncResult = wait(conn, conn.retrieve(retrieveRequest))
            val _retrieveResult = conn.checkRetrieveStatus(asyncResult.getId)
            _retrieveResult
        }.asInstanceOf[RetrieveResult]

        retrieveResult.getMessages match {
            case messages if null != messages && !messages.isEmpty=>
                for(msg <- messages) {
                    //response.warning("Retrieve", "", msg.getFileName, msg.getProblem )
                    logger.warn("Retrieve", "", msg.getFileName, msg.getProblem )
                }
            case _ =>
        }
        //write results to ZIP file
        retrieveResult
    }

    def deploy(zipFile: Array[Byte], deployOptions: DeployOptions ):DeployResult = {
        val conn = getMetadataConnection
        val deployResult = withRetry {
            val asyncResult = wait(conn, conn.deploy(zipFile, deployOptions))
            val _deployResult = conn.checkDeployStatus(asyncResult.getId, true)
            _deployResult
        }.asInstanceOf[DeployResult]

        deployResult
    }

    //TODO - when API v30 is available consider switching to synchronous version of retrieve call
    private val ONE_SECOND = 1000
    private val MAX_NUM_POLL_REQUESTS = config.getProperty("maxPollRequests").getOrElse[String]("50").toInt
    private def wait(connection: MetadataConnection, asyncResult: AsyncResult): AsyncResult = {
        val waitTimeMilliSecs = ONE_SECOND
        var attempts = 0
        var _asyncResult = asyncResult
        while (!_asyncResult.isDone) {
            blocking {
                Thread.sleep(waitTimeMilliSecs)
                logger.info("waiting result")
            }
            attempts += 1
            if (!asyncResult.isDone && ((attempts +1) > MAX_NUM_POLL_REQUESTS)) {
                throw new Exception("Request timed out.  If this is a large set " +
                    "of metadata components, check that the time allowed " +
                    "by maxPollRequests is sufficient.")
            }
            _asyncResult = connection.checkStatus(Array(_asyncResult.getId))(0)
            logger.info("Status is: " + _asyncResult.getState)
        }
        if (AsyncRequestState.Completed != _asyncResult.getState) {
            throw new Exception(_asyncResult.getStatusCode + " msg:" + _asyncResult.getMessage)
        }
        _asyncResult
    }


}
