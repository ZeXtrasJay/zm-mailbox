/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2011 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.account.ldap;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.mail.Address;
import javax.mail.MessagingException;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMultipart;

import com.sun.mail.smtp.SMTPMessage;
import com.zimbra.common.account.ZAttrProvisioning.AutoProvAuthMech;
import com.zimbra.common.mime.MimeConstants;
import com.zimbra.common.mime.shim.JavaMailInternetAddress;
import com.zimbra.common.mime.shim.JavaMailMimeBodyPart;
import com.zimbra.common.mime.shim.JavaMailMimeMultipart;
import com.zimbra.common.service.ServiceException;
import com.zimbra.common.util.L10nUtil;
import com.zimbra.common.util.StringUtil;
import com.zimbra.common.util.ZimbraLog;
import com.zimbra.common.util.L10nUtil.MsgKey;
import com.zimbra.cs.account.Account;
import com.zimbra.cs.account.AccountServiceException;
import com.zimbra.cs.account.AttributeClass;
import com.zimbra.cs.account.AttributeManager;
import com.zimbra.cs.account.Domain;
import com.zimbra.cs.account.EntryCacheDataKey;
import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.account.Provisioning.DirectoryEntryVisitor;
import com.zimbra.cs.account.names.NameUtil.EmailAddress;
import com.zimbra.cs.extension.ExtensionUtil;
import com.zimbra.cs.ldap.IAttributes;
import com.zimbra.cs.ldap.LdapClient;
import com.zimbra.cs.ldap.LdapConstants;
import com.zimbra.cs.ldap.LdapServerConfig;
import com.zimbra.cs.ldap.LdapUsage;
import com.zimbra.cs.ldap.LdapUtilCommon;
import com.zimbra.cs.ldap.SearchLdapOptions;
import com.zimbra.cs.ldap.ZAttributes;
import com.zimbra.cs.ldap.ZLdapContext;
import com.zimbra.cs.ldap.ZLdapFilterFactory;
import com.zimbra.cs.ldap.ZSearchResultEntry;
import com.zimbra.cs.ldap.ZSearchScope;
import com.zimbra.cs.ldap.LdapException.LdapSizeLimitExceededException;
import com.zimbra.cs.ldap.LdapServerConfig.ExternalLdapConfig;
import com.zimbra.cs.ldap.SearchLdapOptions.SearchLdapVisitor;
import com.zimbra.cs.ldap.SearchLdapOptions.StopIteratingException;
import com.zimbra.cs.ldap.ZLdapFilterFactory.FilterId;
import com.zimbra.cs.util.JMSession;

public abstract class AutoProvision {

    protected LdapProv prov;
    protected Domain domain;
    
    protected AutoProvision(LdapProv prov, Domain domain) {
        this.prov = prov;
        this.domain = domain;
    }
    
    abstract Account handle() throws ServiceException;
    
    protected Account createAccount(String acctZimbraName, ExternalEntry externalEntry) 
    throws ServiceException {
        ZAttributes externalAttrs = externalEntry.getAttrs();
        
        Map<String, Object> zimbraAttrs = mapAttrs(externalAttrs);
        
        /*
        // TODO: should we do this?
        String zimbraPassword = RandomPassword.generate();
        zimbraAttrs.put(Provisioning.A_zimbraPasswordMustChange, Provisioning.TRUE);
        */
        String zimbraPassword = null;
        Account acct = null;
        
        try {
            acct = prov.createAccount(acctZimbraName, zimbraPassword, zimbraAttrs);
        } catch (ServiceException e) {
            if (AccountServiceException.ACCOUNT_EXISTS.equals(e.getCode())) {
                // the account already exists, that's fine, just return null
                return null;
            } else {
                throw e;
            }
        }
        
        ZimbraLog.autoprov.info("auto provisioned account: " + acctZimbraName);
        
        ZimbraLog.security.info(ZimbraLog.encodeAttrs(
                new String[] {"cmd", "auto provision Account", "name", acct.getName(), "id", acct.getId()}, zimbraAttrs));  
        
        // send notification email
        try {
            sendNotifMessage(acct, zimbraPassword);
        } catch (ServiceException e) {
            // exception during sending notif email should not fail this method
            ZimbraLog.autoprov.warn("unable to send auto provision notification email", e);
        }
        
        // invoke post create listener if configured
        try {
            AutoProvisionListener listener = AutoProvisionCachedInfo.getInfo(domain).getListener();
            if (listener != null) {
                listener.postCreate(domain, acct, externalEntry.getDN());
            }
        } catch (ServiceException e) {
            // exception during the post create listener should not fail this method
            ZimbraLog.autoprov.warn("encountered error in post auto provision listener", e);
        }
        
        return acct;
    }
    
    private static class AutoProvisionCachedInfo {
        
        private static AutoProvisionCachedInfo getInfo(Domain domain) throws ServiceException {
            AutoProvisionCachedInfo attrMap = 
                (AutoProvisionCachedInfo) domain.getCachedData(EntryCacheDataKey.DOMAIN_AUTO_PROVISION_DATA);
            
            if (attrMap == null) {
                attrMap = new AutoProvisionCachedInfo(domain);
                domain.setCachedData(EntryCacheDataKey.DOMAIN_AUTO_PROVISION_DATA, attrMap);
            }
            
            return attrMap;
        }
        
        private static final String DELIMITER = "=";
        private Map<String, String> attrMap = new HashMap<String, String>();
        private String[] attrsToFetch;
        private AutoProvisionListener listener;
        
        private AutoProvisionCachedInfo(Domain domain) throws ServiceException {
            AttributeManager attrMgr = AttributeManager.getInstance();
            
            // include attrs in schema extension
            Set<String> validAccountAttrs = attrMgr.getAllAttrsInClass(AttributeClass.account);
            
            String[] rules = domain.getAutoProvAttrMap();
            
            for (String rule : rules) {
                String[] parts = rule.split(DELIMITER);
                if (parts.length != 2) {
                    throw ServiceException.FAILURE("invalid value in " + 
                            Provisioning.A_zimbraAutoProvAttrMap + ": " + rule, null);
                }
                
                String externalAttr = parts[0];
                String zimbraAttr = parts[1];
                
                if (!validAccountAttrs.contains(zimbraAttr)) {
                    throw ServiceException.FAILURE("invalid value in " + 
                            Provisioning.A_zimbraAutoProvAttrMap + ": " + rule + 
                            ", not a valid zimbra attribute ", null);
                }
                
                attrMap.put(externalAttr, zimbraAttr);
            }
            
            Set<String> attrs = new HashSet<String>();
            attrs.addAll(attrMap.keySet());
            attrs.add(LdapConstants.ATTR_CREATE_TIMESTAMP);
            String nameMapAttr = domain.getAutoProvAccountNameMap();
            if (nameMapAttr != null) {
                attrs.add(nameMapAttr);
            }
            attrsToFetch = attrs.toArray(new String[0]);
            
            // load listener class and instantiate the handler
            String className = domain.getAutoProvListenerClass();
            if (className != null) {
                try {
                    if (className != null) {
                        listener = ExtensionUtil.findClass(className).asSubclass(AutoProvisionListener.class).newInstance();
                    }
                } catch (ClassNotFoundException e) {
                    ZimbraLog.autoprov.warn("unable to find auto provision listener class " + className, e);
                } catch (InstantiationException e) {
                    ZimbraLog.autoprov.warn("unable to instantiate auto provision listener object of class " + className, e);
                } catch (IllegalAccessException e) {
                    ZimbraLog.autoprov.warn("unable to instantiate auto provision listener object of class " + className, e);
                }
            }
        }
        
        private String getZimbraAttrName(String externalAttrName) {
            return attrMap.get(externalAttrName);
        }
        
        private String[] getAttrsToFetch() {
            return attrsToFetch;
        }
        
        private AutoProvisionListener getListener() {
            return listener;
        }
    }
    
    protected String[] getAttrsToFetch() throws ServiceException {
        return AutoProvisionCachedInfo.getInfo(domain).getAttrsToFetch();
    }
    
    /**
     * map external name to zimbra name for the account to be created in Zimbra.
     * 
     * @param externalAttrs
     * @return
     * @throws ServiceException
     */
    protected String mapName(ZAttributes externalAttrs, String loginName) throws ServiceException {
        String localpart = null;
        
        String localpartAttr = domain.getAutoProvAccountNameMap();
        if (localpartAttr != null) {
            localpart = externalAttrs.getAttrString(localpartAttr);
            if (localpart == null) {
                throw ServiceException.FAILURE("AutoProvision: unable to get localpart: " + loginName, null);
            }
        } else {
            if (loginName == null) {
                throw ServiceException.FAILURE("AutoProvision: unable to map acount name, must configure " +
                        Provisioning.A_zimbraAutoProvAccountNameMap, null);
            }
            EmailAddress emailAddr = new EmailAddress(loginName, false);
            localpart = emailAddr.getLocalPart();
        }
        
        return localpart + "@" + domain.getName();
        
    }
    
    protected Map<String, Object> mapAttrs(ZAttributes externalAttrs) throws ServiceException {
        AutoProvisionCachedInfo attrMap = AutoProvisionCachedInfo.getInfo(domain);
        
        Map<String, Object> extAttrs = externalAttrs.getAttrs();
        Map<String, Object> zimbraAttrs = new HashMap<String, Object>();
        
        for (Map.Entry<String, Object> extAttr : extAttrs.entrySet()) {
            String extAttrName = extAttr.getKey();
            Object attrValue = extAttr.getValue();
            
            String zimbraAttrName = attrMap.getZimbraAttrName(extAttrName);
            if (zimbraAttrName != null) {
                if (attrValue instanceof String) {
                    StringUtil.addToMultiMap(zimbraAttrs, zimbraAttrName, (String) attrValue);
                } else if (attrValue instanceof String[]) {
                    for (String value : (String[]) attrValue) {
                        StringUtil.addToMultiMap(zimbraAttrs, zimbraAttrName, value);
                    }
                }
            }
        }
        
        return zimbraAttrs;
    }
    
    protected ZAttributes getExternalAttrsByDn(String dn) throws ServiceException {
        String url = domain.getAutoProvLdapURL();
        boolean wantStartTLS = domain.isAutoProvLdapStartTlsEnabled();
        String adminDN = domain.getAutoProvLdapAdminBindDn();
        String adminPassword = domain.getAutoProvLdapAdminBindPassword();
        
        ExternalLdapConfig config = new ExternalLdapConfig(url, wantStartTLS, 
                null, adminDN, adminPassword, null, "auto provision account");
        
        ZLdapContext zlc = null;
        
        try {
            zlc = LdapClient.getExternalContext(config, LdapUsage.AUTO_PROVISION);
            return prov.getHelper().getAttributes(zlc, dn, getAttrsToFetch());
        } finally {
            LdapClient.closeContext(zlc);
        }
    }
    
    protected static class ExternalEntry {
        private String dn;
        private ZAttributes attrs;
        
        ExternalEntry(String dn, ZAttributes attrs) {
            this.dn = dn;
            this.attrs = attrs;
        }
        
        String getDN() {
            return dn;
        }
        
        ZAttributes getAttrs() {
            return attrs;
        }
    }

    protected ExternalEntry getExternalAttrsByName(String loginName) throws ServiceException {
        String url = domain.getAutoProvLdapURL();
        boolean wantStartTLS = domain.isAutoProvLdapStartTlsEnabled();
        String adminDN = domain.getAutoProvLdapAdminBindDn();
        String adminPassword = domain.getAutoProvLdapAdminBindPassword();
        String[] attrs = getAttrsToFetch();
        
        // always use the admin bind DN/password, not the user's bind DN/password
        ExternalLdapConfig config = new ExternalLdapConfig(url, wantStartTLS, 
                null, adminDN, adminPassword, null, "auto provision account");
        
        ZLdapContext zlc = null;
        
        try {
            zlc = LdapClient.getExternalContext(config, LdapUsage.AUTO_PROVISION);
            
            String searchFilterTemplate = domain.getAutoProvLdapSearchFilter();
            if (searchFilterTemplate != null) {
                // get attrs by search
                String searchBase = domain.getAutoProvLdapSearchBase();
                if (searchBase == null) {
                    searchBase = LdapConstants.DN_ROOT_DSE;
                }
                String searchFilter = LdapUtilCommon.computeAuthDn(loginName, searchFilterTemplate);
                ZimbraLog.autoprov.debug("AutoProvision: computed search filter" + searchFilter);
                ZSearchResultEntry entry = prov.getHelper().searchForEntry(
                        searchBase, ZLdapFilterFactory.getInstance().fromFilterString(
                                FilterId.AUTO_PROVISION_GET_EXTERNAL_ATTRS, searchFilter), 
                        zlc, attrs);
                return new ExternalEntry(entry.getDN(), entry.getAttributes());
            }
            
            String bindDNTemplate = domain.getAutoProvLdapBindDn();
            if (bindDNTemplate != null) {
                // get attrs by external DN template
                String dn = LdapUtilCommon.computeAuthDn(loginName, bindDNTemplate);
                ZimbraLog.autoprov.debug("AutoProvision: computed external DN" + dn);
                return new ExternalEntry(dn, prov.getHelper().getAttributes(zlc, dn, attrs));
            }
            
        } finally {
            LdapClient.closeContext(zlc);
        }
        
        throw ServiceException.FAILURE("One of " + Provisioning.A_zimbraAutoProvLdapBindDn + 
                " or " + Provisioning.A_zimbraAutoProvLdapSearchFilter + " must be set", null);
    }
    
    protected void sendNotifMessage(Account acct, String password) throws ServiceException {
        String from = domain.getAutoProvNotificationFromAddress();
        if (from == null) {
            // if From address is configured, notification is not sent.
            // TODO: should we use a seperate boolean control?
            return;
        }
        
        String toAddr = acct.getName();
        
        try {
            SMTPMessage out = new SMTPMessage(JMSession.getSmtpSession());
            
            InternetAddress addr = null;
            try {
                addr = new JavaMailInternetAddress(from);
            } catch (AddressException e) {
                // log and try the next one
                ZimbraLog.autoprov.warn("invalid address in " +
                        Provisioning.A_zimbraAutoProvNotificationFromAddress, e);
            }
            
            Address fromAddr = addr;
            Address replyToAddr = addr;

            // From
            out.setFrom(fromAddr);

            // Reply-To
            out.setReplyTo(new Address[]{replyToAddr});

            // To
            
            out.setRecipient(javax.mail.Message.RecipientType.TO, new JavaMailInternetAddress(toAddr));

            // Date
            out.setSentDate(new Date());

            // Subject
            Locale locale = acct.getLocale();
            String subject = L10nUtil.getMessage(MsgKey.accountAutoProvisionedSubject, locale);
            out.setSubject(subject);

            // body
            MimeMultipart mmp = new JavaMailMimeMultipart("alternative");

            // TEXT part (add me first!)
            String text = L10nUtil.getMessage(MsgKey.accountAutoProvisionedBody, locale, acct.getDisplayName());
            MimeBodyPart textPart = new JavaMailMimeBodyPart();
            textPart.setText(text, MimeConstants.P_CHARSET_UTF8);
            mmp.addBodyPart(textPart);

            // HTML part
            StringBuilder html = new StringBuilder();
            html.append("<h4>\n");
            html.append("<p>" + L10nUtil.getMessage(MsgKey.accountAutoProvisionedBody, locale, acct.getDisplayName()) + "</p>\n");
            html.append("</h4>\n");
            html.append("\n");

            MimeBodyPart htmlPart = new JavaMailMimeBodyPart();
            htmlPart.setDataHandler(new DataHandler(new HtmlPartDataSource(html.toString())));
            mmp.addBodyPart(htmlPart);
            out.setContent(mmp);
            
            // send it
            Transport.send(out);

            // log
            Address[] rcpts = out.getRecipients(javax.mail.Message.RecipientType.TO);
            StringBuilder rcptAddr = new StringBuilder();
            for (Address a : rcpts)
                rcptAddr.append(a.toString());
            ZimbraLog.autoprov.info("auto provision notification sent rcpt='" + rcptAddr + "' Message-ID=" + out.getMessageID());

        } catch (MessagingException e) {
            ZimbraLog.autoprov.warn("send auto provision notification failed rcpt='" + toAddr +"'", e);
        }
    }

    private static abstract class MimePartDataSource implements DataSource {

        private String mText;
        private byte[] mBuf = null;

        public MimePartDataSource(String text) {
            mText = text;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            synchronized(this) {
                if (mBuf == null) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    OutputStreamWriter wout =
                        new OutputStreamWriter(buf, MimeConstants.P_CHARSET_UTF8);
                    String text = mText;
                    wout.write(text);
                    wout.flush();
                    mBuf = buf.toByteArray();
                }
            }
            ByteArrayInputStream in = new ByteArrayInputStream(mBuf);
            return in;
        }

        @Override
        public OutputStream getOutputStream() {
            throw new UnsupportedOperationException();
        }
    }

    private static class HtmlPartDataSource extends MimePartDataSource {
        private static final String CONTENT_TYPE =
            MimeConstants.CT_TEXT_HTML + "; " + MimeConstants.P_CHARSET + "=" + MimeConstants.P_CHARSET_UTF8;
        private static final String NAME = "HtmlDataSource";

        HtmlPartDataSource(String text) {
            super(text);
        }

        @Override
        public String getContentType() {
            return CONTENT_TYPE;
        }

        @Override
        public String getName() {
            return NAME;
        }
    }
    
    /*
     * entries are returned in DirectoryEntryVisitor interface.
     */
    static void searchAutoProvDirectory(LdapProv prov, Domain domain, 
            String filter, String name, String createTimestampLaterThan,
            String[] returnAttrs, int maxResults, final DirectoryEntryVisitor visitor) 
    throws ServiceException {

        SearchLdapVisitor ldapVisitor = new SearchLdapVisitor() {
            @Override
            public void visit(String dn, Map<String, Object> attrs, IAttributes ldapAttrs)
            throws StopIteratingException {
                visitor.visit(dn, attrs);
            }
        };
        
        searchAutoProvDirectory(prov, domain, filter,  name,  createTimestampLaterThan,
                returnAttrs,  maxResults,  ldapVisitor);
    }
    
    /**
     * Search the external auto provision LDAP source
     * 
     * Only one of filter or name can be provided.  
     * 
     * - if name is provided, the search filter will be zimbraAutoProvLdapSearchFilter with place 
     *   holders filled with the name.
     * 
     * - if filter is provided, the provided filter will be the search filter.
     * 
     * - if neither is provided, the search filter will be zimbraAutoProvLdapSearchFilter with 
     *   place holders filled with "*".   If createTimestampLaterThan 
     *   is provided, the search filter will be ANDed with (createTimestamp >= {timestamp}) 
     *
     */
    static void searchAutoProvDirectory(LdapProv prov, Domain domain, 
            String filter, String name, String createTimestampLaterThan,
            String[] returnAttrs, int maxResults, SearchLdapVisitor ldapVisitor)
    throws ServiceException {
        // use either filter or name, make sure only one is provided
        if ((filter != null) && (name != null)) {
            throw ServiceException.INVALID_REQUEST("only one of filter or name can be provided", null);
        }

        String url = domain.getAutoProvLdapURL();
        boolean wantStartTLS = domain.isAutoProvLdapStartTlsEnabled();
        String adminDN = domain.getAutoProvLdapAdminBindDn();
        String adminPassword = domain.getAutoProvLdapAdminBindPassword();
        String searchBase = domain.getAutoProvLdapSearchBase();
        String searchFilterTemplate = domain.getAutoProvLdapSearchFilter();
        
        if (searchBase == null) {
            searchBase = LdapConstants.DN_ROOT_DSE;
        }
        
        ExternalLdapConfig config = new ExternalLdapConfig(url, wantStartTLS, 
                null, adminDN, adminPassword, null, "search auto provision directory");
        
        ZLdapContext zlc = null;
        try {
            zlc = LdapClient.getExternalContext(config, LdapUsage.AUTO_PROVISION_ADMIN_SEARCH);
            
            String searchFilter = null;
            
            if (name != null) {
                if (searchFilterTemplate == null) {
                    throw ServiceException.INVALID_REQUEST(
                            "search filter template is not set on domain " + domain.getName(), null);
                }
                searchFilter = LdapUtilCommon.computeAuthDn(name, searchFilterTemplate);
            } else if (filter != null) {
                searchFilter = filter;
            } else {
                if (searchFilterTemplate == null) {
                    throw ServiceException.INVALID_REQUEST(
                            "search filter template is not set on domain " + domain.getName(), null);
                }
                searchFilter = LdapUtilCommon.computeAuthDn("*", searchFilterTemplate);
                if (createTimestampLaterThan != null) {
                    searchFilter = "(&" + searchFilter + "(createTimestamp>=" + createTimestampLaterThan + "))";
                }
            }
            
            SearchLdapOptions searchOptions = new SearchLdapOptions(searchBase, searchFilter, 
                    returnAttrs, maxResults, null, ZSearchScope.SEARCH_SCOPE_SUBTREE, ldapVisitor);
            
            zlc.searchPaged(searchOptions);
        } catch (LdapSizeLimitExceededException e) {
            throw AccountServiceException.TOO_MANY_SEARCH_RESULTS("too many search results returned", e);    
        } finally {
            LdapClient.closeContext(zlc);
        }
    }
   
}



