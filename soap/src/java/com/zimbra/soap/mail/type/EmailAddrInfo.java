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

import com.google.common.base.MoreObjects;
import com.zimbra.common.soap.MailConstants;

// See ParseMimeMessage.MessageAddresses.
//
@XmlAccessorType(XmlAccessType.NONE)
public class EmailAddrInfo {

    /**
     * @zm-api-field-tag email-addr
     * @zm-api-field-description Email address
     */
    @XmlAttribute(name=MailConstants.A_ADDRESS /* a */, required=true)
    private final String address;

    /**
     * @zm-api-field-tag address-type
     * @zm-api-field-description Optional Address type - (f)rom, (t)o, (c)c, (b)cc, (r)eply-to,
     * (s)ender, read-receipt (n)otification, (rf) resent-from
     */
    @XmlAttribute(name=MailConstants.A_ADDRESS_TYPE /* t */, required=false)
    private String addressType;

    /**
     * @zm-api-field-tag personal-name
     * @zm-api-field-description The comment/name part of an address
     */
    @XmlAttribute(name=MailConstants.A_PERSONAL /* p */, required=false)
    private String personal;

    /**
     * no-argument constructor wanted by JAXB
     */
    @SuppressWarnings("unused")
    private EmailAddrInfo() {
        this((String) null);
    }

    public EmailAddrInfo(String address) {
        this.address = address;
    }

    public static EmailAddrInfo createForAddressPersonalAndAddressType(String address,
            String personalName, String addressType) {
        EmailAddrInfo eai = new EmailAddrInfo(address);
        eai.setPersonal(personalName);
        eai.setAddressType(addressType);
        return eai;
    }

    public void setAddressType(String addressType) { this.addressType = addressType; }
    public void setPersonal(String personal) { this.personal = personal; }
    public String getAddress() { return address; }
    public String getAddressType() { return addressType; }
    public String getPersonal() { return personal; }

    public MoreObjects.ToStringHelper addToStringInfo(MoreObjects.ToStringHelper helper) {
        return helper
            .add("address", address)
            .add("addressType", addressType)
            .add("personal", personal);
    }

    @Override
    public String toString() {
        return addToStringInfo(MoreObjects.toStringHelper(this)).toString();
    }
}
