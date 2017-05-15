/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011, 2012, 2013, 2014, 2016 Synacor, Inc.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software Foundation,
 * version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License along with this program.
 * If not, see <https://www.gnu.org/licenses/>.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.soap.mail.type;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlEnum;
import javax.xml.bind.annotation.XmlTransient;
import javax.xml.bind.annotation.XmlValue;

import org.codehaus.jackson.annotate.JsonPropertyOrder;

import com.google.common.base.Objects;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.soap.MailConstants;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.soap.type.ZmBoolean;

@XmlAccessorType(XmlAccessType.NONE)
@XmlTransient
public class FilterAction {

    /**
     * @zm-api-field-tag index
     * @zm-api-field-description Index - specifies a guaranteed order for the action elements
     */
    @XmlAttribute(name=MailConstants.A_INDEX /* index */, required=false)
    private int index = 0;

    public void setIndex(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this).add("index", index).toString();
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class DiscardAction extends FilterAction {
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class KeepAction extends FilterAction {
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class StopAction extends FilterAction {
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class FileIntoAction extends FilterAction {

        /**
         * @zm-api-field-tag folder-path
         * @zm-api-field-description Folder path
         */
        @XmlAttribute(name=MailConstants.A_FOLDER_PATH, required=false)
        private final String folder;

        /**
         * @zm-api-field-tag copy
         * @zm-api-field-description If true, item will be copied to the new location,
         *                           leaving the original in place. See https://tools.ietf.org/html/rfc3894
         *                           "Sieve Extension: Copying Without Side Effects"
         */
        @XmlAttribute(name=MailConstants.A_COPY /* copy */, required=false)
        private ZmBoolean copy;

        @SuppressWarnings("unused")
        private FileIntoAction() {
            this(null);
        }

        public FileIntoAction(String folder) {
            this.folder = folder;
            this.copy = ZmBoolean.FALSE;
        }

        public FileIntoAction(String folder, boolean copy) {
            this.folder = folder;
            this.copy = ZmBoolean.fromBool(copy, false);
        }

        public String getFolder() {
            return folder;
        }

        public boolean isCopy() {
            return ZmBoolean.toBool(copy, false);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("folder", folder).add("copy", copy).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    @JsonPropertyOrder({ "flagName", "index" })
    public static final class FlagAction extends FilterAction {

        /**
         * @zm-api-field-tag flag-name-flagged|read|priority
         * @zm-api-field-description Flag name - <b>flagged|read|priority</b>
         */
        @XmlAttribute(name=MailConstants.A_FLAG_NAME, required=false)
        private final String flag;

        @SuppressWarnings("unused")
        private FlagAction() {
            this(null);
        }

        public FlagAction(String flag) {
            this.flag = flag;
        }

        public String getFlag() {
            return flag;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("flag", flag).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class RedirectAction extends FilterAction {

        /**
         * @zm-api-field-tag email-address
         * @zm-api-field-description Email address
         */
        @XmlAttribute(name=MailConstants.A_ADDRESS, required=false)
        private final String address;

        /**
         * @zm-api-field-tag copy
         * @zm-api-field-description If true, item's copy will be redirected,
         *                           leaving the original in place.See https://tools.ietf.org/html/rfc3894
         *                           "Sieve Extension: Copying Without Side Effects"
         */
        @XmlAttribute(name=MailConstants.A_COPY /* copy */, required=false)
        private ZmBoolean copy;

        @SuppressWarnings("unused")
        private RedirectAction() {
            this(null);
        }

        public RedirectAction(String addr) {
            address = addr;
            this.copy = ZmBoolean.FALSE;
        }

        public RedirectAction(String addr, boolean copy) {
            address = addr;
            this.copy = ZmBoolean.fromBool(copy, false);
        }

        public String getAddress() {
            return address;
        }

        public boolean isCopy() {
            return ZmBoolean.toBool(copy, false);
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("address", address).add("copy", copy).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class NotifyAction extends FilterAction {

        /**
         * @zm-api-field-tag email-address
         * @zm-api-field-description Email address
         */
        @XmlAttribute(name=MailConstants.A_ADDRESS, required=false)
        private String address;

        /**
         * @zm-api-field-tag subject-template
         * @zm-api-field-description Subject template
         * <br />
         * Can contain variables such as ${SUBJECT}, ${TO}, ${CC}, etc
         * (basically ${any-header-name}; case not important), plus ${BODY} (text body of the message).
         */
        @XmlAttribute(name=MailConstants.A_SUBJECT, required=false)
        private String subject;

        /**
         * @zm-api-field-tag max-body-size-bytes
         * @zm-api-field-description Maximum body size in bytes
         */
        @XmlAttribute(name=MailConstants.A_MAX_BODY_SIZE, required=false)
        private Integer maxBodySize;

        /**
         * @zm-api-field-tag body-template
         * @zm-api-field-description Body template
         * <br />
         * Can contain variables such as ${SUBJECT}, ${TO}, ${CC}, etc
         * (basically ${any-header-name}; case not important), plus ${BODY} (text body of the message).
         */
        @XmlElement(name=MailConstants.E_CONTENT, required=false)
        private String content;

        /**
         * @zm-api-field-tag comma-sep-header-names|*
         * @zm-api-field-description Optional - Either "*" or a comma-separated list of header names.
         */
        @XmlAttribute(name=MailConstants.A_ORIG_HEADERS, required=false)
        private String origHeaders;

        public void setAddress(String address) {
            this.address = address;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public void setMaxBodySize(Integer maxBodySize) {
            this.maxBodySize = maxBodySize;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public void setOrigHeaders(String origHeaders) {
            this.origHeaders = origHeaders;
        }

        public String getAddress() {
            return address;
        }

        public String getSubject() {
            return subject;
        }

        public int getMaxBodySize() {
            return maxBodySize != null ? maxBodySize : -1;
        }

        public String getContent() {
            return content;
        }

        public String getOrigHeaders() {
            return origHeaders;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("address", address)
                .add("subject", subject)
                .add("maxBodySize", maxBodySize)
                .add("content", content)
                .add("origHeaders", origHeaders)
                .toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class RFCCompliantNotifyAction extends FilterAction {

        /**
         * @zm-api-field-tag from
         * @zm-api-field-description Notify Tag ":from"
         */
        @XmlAttribute(name=MailConstants.A_FROM, required=false)
        private String from;

        /**
         * @zm-api-field-tag importance
         * @zm-api-field-description Notify Tag ":importance"
         */
        @XmlAttribute(name=MailConstants.A_IMPORTANCE, required=false)
        private String importance;

        /**
         * @zm-api-field-tag options
         * @zm-api-field-description Notify Tag ":options"
         */
        @XmlAttribute(name=MailConstants.A_OPTIONS, required=false)
        private String options;

        /**
         * @zm-api-field-tag message
         * @zm-api-field-description Notify Tag ":message"
         */
        @XmlAttribute(name=MailConstants.A_MESSAGE, required=false)
        private String message;

        /**
         * @zm-api-field-tag method
         * @zm-api-field-description Notify Parameter "method"
         */
        @XmlElement(name=MailConstants.A_METHOD, required=true)
        private String method;

        public void setFrom(String from) {
            this.from = from;
        }

        public void setImportance(String importance) {
            this.importance = importance;
        }

        public void setOptions(String options) {
            this.options = options;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getFrom() {
            return from;
        }

        public String getImportance() {
            return importance;
        }

        public String getOptions() {
            return options;
        }

        public String getMessage() {
            return message;
        }

        public String getMethod() {
            return method;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this)
                .add("from", from)
                .add("importance", importance)
                .add("options", options)
                .add("message", message)
                .add("method", method)
                .toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class TagAction extends FilterAction {

        /**
         * @zm-api-field-tag tag-name
         * @zm-api-field-description Tag name
         */
        @XmlAttribute(name=MailConstants.A_TAG_NAME, required=true)
        private final String tag;

        @SuppressWarnings("unused")
        private TagAction() {
            this(null);
        }

        public TagAction(String tag) {
            this.tag = tag;
        }

        public String getTag() {
            return tag;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("tag", tag).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class ReplyAction extends FilterAction {

        /**
         * @zm-api-field-tag body-template
         * @zm-api-field-description Body template
         * <br />
         * Can contain variables such as ${SUBJECT}, ${TO}, ${CC}, etc
         * (basically ${any-header-name}; case not important), plus ${BODY} (text body of the message).
         */
        @XmlElement(name=MailConstants.E_CONTENT, required=false)
        private final String content;

        @SuppressWarnings("unused")
        private ReplyAction() {
            this(null);
        }

        public ReplyAction(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("content", content).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static class RejectAction extends FilterAction {
        public static final String TEXT_TEMPLATE = "text:";

        /**
         * @zm-api-field-tag content
         * @zm-api-field-description message text
         */
        @XmlValue
        protected final String content;

        @SuppressWarnings("unused")
        private RejectAction() {
            this(null);
        }

        public RejectAction(String content) {
            this.content = content;
        }

        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("content", content).toString();
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static final class ErejectAction extends RejectAction {
        @SuppressWarnings("unused")
        private ErejectAction() {
            this(null);
        }

        public ErejectAction(String value) {
            super(value);
        }
    }

    @XmlAccessorType(XmlAccessType.NONE)
    public static class LogAction extends FilterAction {
        @XmlEnum
        public enum LogLevel {
            fatal,
            error,
            warn,
            info,
            debug,
            trace;

            public static LogLevel fromString(String s) throws ServiceException {
                try {
                    return LogLevel.valueOf(s);
                } catch (IllegalArgumentException e) {
                    throw ServiceException.INVALID_REQUEST("unknown key: "+s, e);
                }
            }

            public LogLevel toKeyLogLevel()
            throws ServiceException {
                return LogLevel.fromString(this.name());
            }
        }

        /**
         * @zm-api-field-tag logLevel
         * @zm-api-field-description Log level - <b>fatal|error|warn|info|debug|trace</b>, info is default if not specified.
         */
        @XmlAttribute(name=MailConstants.A_LEVEL/* level */, required=false)
        private LogLevel level;

        /**
         * @zm-api-field-tag content
         * @zm-api-field-description message text
         */
        @XmlValue
        protected final String content;

        @SuppressWarnings("unused")
        private LogAction() {
            this(null, null);
        }

        public LogAction(LogLevel level, String content) {
            this.level = level;
            this.content = content;
        }

        /**
         * @return the level
         */
        public LogLevel getLevel() {
            return level;
        }

        /**
         * @param level the level to set
         */
        public void setLevel(LogLevel level) {
            this.level = level;
        }

        /**
         * @return the content
         */
        public String getContent() {
            return content;
        }

        @Override
        public String toString() {
            return Objects.toStringHelper(this).add("level", level).add("content", content).toString();
        }
    }
}
