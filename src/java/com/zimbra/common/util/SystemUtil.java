/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2005, 2006, 2007 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Yahoo! Public License
 * Version 1.0 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */

package com.zimbra.common.util;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SystemUtil {

    public static String getStackTrace() {
        return getStackTrace(new Throwable());
    }
    
    public static String getStackTrace(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
    
    /**
     * Returns the innermost exception wrapped by
     * <tt>t</tt>.  The innermost exception is found by iterating
     * the exceptions returned by {@link Throwable#getCause()}.
     * 
     * @return the innermost exception, or <tt>null</tt> if <tt>t</tt>
     * is <tt>null</tt>.
     */
    public static Throwable getInnermostException(Throwable t) {
        if (t == null) {
            return null;
        }
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
