/**
 * Copyright (c) 2000 The JA-SIG Collaborative.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. Redistributions of any form whatsoever must retain the following
 *    acknowledgment:
 *    "This product includes software developed by the JA-SIG Collaborative
 *    (http://www.jasig.org/)."
 *
 * THIS SOFTWARE IS PROVIDED BY THE JA-SIG COLLABORATIVE "AS IS" AND ANY
 * EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE JA-SIG COLLABORATIVE OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package org.jasig.portal.channels;

import org.jasig.portal.*;
import org.jasig.portal.utils.XSLT;
import org.jasig.portal.security.IPerson;
import org.xml.sax.DocumentHandler;
import java.io.File;
import javax.servlet.http.HttpSession;

/** <p>Allows a user to logon to the portal.  Logon info is posted to
 *  <code>authenticate.jsp</code></p>
 * @author Ken Weiner, kweiner@interactivebusiness.com
 * @version $Revision$
 */
public class CLogin implements IPrivilegedChannel
{
  private ChannelStaticData staticData;
  private ChannelRuntimeData runtimeData;
  private String channelName = "Log in...";
  private String media;
  private static final String fs = File.separator;
  private static final String sslLocation = UtilitiesBean.getPortalBaseDir() + "webpages" + fs + "stylesheets" + fs + "org" + fs + "jasig" + fs + "portal" + fs + "channels" + fs + "CLogin" + fs + "CLogin.ssl";
  private boolean bAuthenticated = false;
  private boolean bAuthorizationAttemptFailed = false;

  public CLogin()
  {
  }

  public ChannelSubscriptionProperties getSubscriptionProperties()
  {
    ChannelSubscriptionProperties csb = new ChannelSubscriptionProperties();
    csb.setName(this.channelName);
    return csb;
  }

  public void setPortalControlStructures(PortalControlStructures pcs)
  {
    HttpSession session = pcs.getHttpSession();
    IPerson person = (IPerson)session.getAttribute("up_person");
    String authorizationAttempted = (String)session.getAttribute("up_authorizationAttempted");

    if (person != null)
      bAuthenticated = true;

    if (authorizationAttempted != null)
      bAuthorizationAttemptFailed = true;
  }

  public ChannelRuntimeProperties getRuntimeProperties()
  {
    return new ChannelRuntimeProperties();
  }

  public void receiveEvent(LayoutEvent ev)
  {
  }

  public void setStaticData (ChannelStaticData sd)
  {
    this.staticData = sd;
  }

  public void setRuntimeData (ChannelRuntimeData rd)
  {
    this.runtimeData = rd;

    media = runtimeData.getMedia();
  }

  public void renderXML (DocumentHandler out) throws PortalException
  {
    String userName = null;
    StringBuffer sb = new StringBuffer ("<?xml version='1.0'?>\n");
    sb.append("<login-status>\n");

    if (bAuthorizationAttemptFailed && !bAuthenticated)
       sb.append("  <failure userName=\"" + userName + "\"/>\n");

    sb.append("</login-status>\n");

    try
    {
        XSLT.transform(out, media, sb.toString(), sslLocation, "login", null);
    }
    catch (Exception e)
    {
        throw new GeneralRenderingException(e.getMessage());
    }
  }
}
