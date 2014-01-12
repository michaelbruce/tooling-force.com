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

import com.sforce.soap.partner.sobject.SObject
import com.neowit.utils.Logging
import java.util.TimeZone
import java.io.File

object MetadataType extends Logging {
    import com.sforce.soap.metadata.FileProperties

    def getKey(props: FileProperties): String = {
        val xmlName = props.getType
        val name = getName(props)

        xmlName + "." + name
    }
    private def getName(props: FileProperties): String = {
        val fName = new File(props.getFileName).getName
        val name = fName.substring(0, fName.lastIndexWhere(_ == '.'))
        name
    }

    def getValueMap(props: FileProperties):Map[String, String] = {
        //2013-09-25T09:31:08.000Z - simulate output from datatime returned by SOQL query
        val lastModifiedDateText = getLastModifiedDateText(props)
        val lastModifiedDateMills = String.valueOf(getLastModifiedDateMills(props))
        Map("Id" -> props.getId, "Name" -> getName(props), "LastModifiedDate" -> lastModifiedDateText, "LastModifiedDateMills" -> lastModifiedDateMills)
    }

    def getLastModifiedDateText(props: FileProperties) = {
        val dateFormatGmt = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
        dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT"))
        val lastModifiedDate = dateFormatGmt.format(props.getLastModifiedDate.getTime) + ".000Z"
        lastModifiedDate
    }
    def getLastModifiedDateMills(props: FileProperties) = {
        val lastModifiedDate = props.getLastModifiedDate.getTime.getTime
        lastModifiedDate
    }

}
class MetadataType(val xmlName: String) extends Logging{

    def getLastModifiedDate(obj: SObject): String = {
        obj.getField("LastModifiedDate") match {
            case d if null != d => d.toString
            case _ => "1970-01-01T00:00:00.000Z"
        }
    }

    def getValueMap(obj: SObject):Map[String, String] = {
        val lastModifiedDate = getLastModifiedDate(obj).toString
        obj.getType match {
            case "StaticResource" =>
                Map("Id" -> obj.getId, "Name" -> obj.getField("Name").toString, "LastModifiedDate" -> lastModifiedDate)
            case _ =>
                Map("Id" -> obj.getId, "Name" -> obj.getField("Name").toString, "ApiVersion" -> obj.getField("ApiVersion").toString, "LastModifiedDate" -> lastModifiedDate)
        }
    }
    def getQueriableFields = {
        val fNames = List("Id", "Name", "LastModifiedDate")
        xmlName match {
            case "StaticResource" => fNames
            case _ =>
                "ApiVersion" :: fNames
        }

    }
    def getKey(obj: SObject): String = {xmlName + "." + obj.getField("Name")}
}
