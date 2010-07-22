/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.layout.dlm.remoting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jasig.portal.IChannelRegistryStore;
import org.jasig.portal.IUserIdentityStore;
import org.jasig.portal.PortalException;
import org.jasig.portal.StructureStylesheetUserPreferences;
import org.jasig.portal.ThemeStylesheetUserPreferences;
import org.jasig.portal.UserPreferencesManager;
import org.jasig.portal.channel.IChannelDefinition;
import org.jasig.portal.fragment.subscribe.IUserFragmentSubscription;
import org.jasig.portal.fragment.subscribe.dao.IUserFragmentSubscriptionDao;
import org.jasig.portal.layout.IUserLayoutManager;
import org.jasig.portal.layout.IUserLayoutStore;
import org.jasig.portal.layout.UserLayoutStoreFactory;
import org.jasig.portal.layout.dlm.Constants;
import org.jasig.portal.layout.dlm.UserPrefsHandler;
import org.jasig.portal.layout.node.IUserLayoutChannelDescription;
import org.jasig.portal.layout.node.IUserLayoutFolderDescription;
import org.jasig.portal.layout.node.IUserLayoutNodeDescription;
import org.jasig.portal.layout.node.UserLayoutChannelDescription;
import org.jasig.portal.layout.node.UserLayoutFolderDescription;
import org.jasig.portal.security.IPerson;
import org.jasig.portal.security.PersonFactory;
import org.jasig.portal.security.provider.RestrictedPerson;
import org.jasig.portal.user.IUserInstance;
import org.jasig.portal.user.IUserInstanceManager;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Provides targets for AJAX preference setting calls.
 * 
 * @author jennifer.bourey@yale.edu
 * @version $Revision$ $Date$
 */
@Controller
@RequestMapping("/layout")
public class UpdatePreferencesServlet implements InitializingBean {

	protected final Log log = LogFactory.getLog(getClass());
	
    private IUserLayoutStore userLayoutStore;

	private IChannelRegistryStore channelRegistryStore;
	
	@Autowired(required = true)
	public void setChannelRegistryStore(IChannelRegistryStore channelRegistryStore) {
	    this.channelRegistryStore = channelRegistryStore;
	}

    private IUserIdentityStore userStore;

    @Autowired(required = true)
    public void setUserIdentityStore(IUserIdentityStore userStore) {
        this.userStore = userStore;
    }
    
    private IUserFragmentSubscriptionDao userFragmentInfoDao;
    
    @Autowired(required = true)
    public void setUserFragmentInfoDao(IUserFragmentSubscriptionDao userFragmentInfoDao) {
        this.userFragmentInfoDao = userFragmentInfoDao;
    }
    
    private IUserInstanceManager userInstanceManager;
    
    @Autowired(required = true)
    public void setUserInstanceManager(IUserInstanceManager userInstanceManager) {
        this.userInstanceManager = userInstanceManager;
    }
    
	// default tab name
	protected final static String DEFAULT_TAB_NAME = "New Tab";
	protected final static String ACTIVE_TAB_PARAM = "activeTab";

    public void afterPropertiesSet() throws Exception {
        this.userLayoutStore = UserLayoutStoreFactory.getUserLayoutStoreImpl();
    }

	/**
	 * Remove an element from the layout.
	 * 
	 * @param request
	 * @param response
	 * @return
	 * @throws IOException
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=removeElement")
    public ModelAndView removeElement(HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

        try {
            
            // if the element ID starts with the fragment prefix and is a folder, 
            // attempt first to treat it as a pulled fragment subscription
            String elementId = request.getParameter("elementID");
            if (elementId != null && elementId.startsWith(Constants.FRAGMENT_ID_USER_PREFIX) && 
                    ulm.getNode( elementId ) instanceof org.jasig.portal.layout.node.UserLayoutFolderDescription) {
                
                removeSubscription(per, elementId, ulm);
                
            } else {
                // Delete the requested element node.  This code is the same for 
                // all node types, so we can just have a generic action.
               ulm.deleteNode(elementId);
            }

            saveLayout(per, ulm, upm, null);

            return new ModelAndView("jsonView", Collections.EMPTY_MAP);
            
        } catch (Exception e) {
            log.warn("Failed to remove element from layout", e);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
    }

    /**
     * Subscribe a user to a pre-formatted tab (pulled DLM fragment).
     * 
     * @param request
     * @param response
     * @return
     * @throws IOException
     */
    @RequestMapping(method = RequestMethod.POST, params = "action=subscribeToTab")
    public ModelAndView subscribeToTab(HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

        // Get the fragment owner's name from the request and construct 
        // an IPerson object representing that user
        String fragmentOwnerName = request.getParameter("sourceID");
        if (StringUtils.isBlank(fragmentOwnerName)) {
            log.warn("Attempted to subscribe to tab with null owner ID");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
        RestrictedPerson fragmentOwner = PersonFactory.createRestrictedPerson();
        fragmentOwner.setUserName(fragmentOwnerName);

        // Mark the currently-authenticated user as subscribed to this fragment.
        // If an inactivated fragment registration already exists, update it
        // as an active subscription.  Otherwise, create a new fragment
        // subscription.
        IUserFragmentSubscription userFragmentInfo = userFragmentInfoDao
            .getUserFragmentInfo(per, fragmentOwner);
        if (userFragmentInfo == null) {
            userFragmentInfo = userFragmentInfoDao.createUserFragmentInfo(per,
                    fragmentOwner);
        } else {
            userFragmentInfo.setActive(true);
            userFragmentInfoDao.updateUserFragmentInfo(userFragmentInfo);
        }
        
        try {
            ulm.loadUserLayout(true);

            moveSubscribedTab(per, upm, ulm, fragmentOwner, request,
                    response);
            return new ModelAndView("jsonView", Collections.EMPTY_MAP);
        } catch (Exception e) {
            log.warn("Error subscribing to fragment owned by "
                    + fragmentOwnerName, e);
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }

    }

    /**
     * Move a tab left or right.
     * 
     * @param per
     * @param upm
     * @param ulm
     * @param request
     * @param response
     * @throws PortalException
     * @throws IOException
     */
    private void moveSubscribedTab(IPerson per, UserPreferencesManager upm,
            IUserLayoutManager ulm, IPerson fragmentOwner, HttpServletRequest request,
            HttpServletResponse response) throws PortalException, IOException, Exception {

        // get the target node this new tab should be moved after
        String destinationId = request.getParameter("elementID");

        // get the user layout for the currently-authenticated user
        int uid = userStore.getPortalUID(fragmentOwner, false);
        Document userLayout = userLayoutStore.getUserLayout(per, upm.getUserPreferences().getProfile());

        // attempt to find the new subscribed tab in the layout so we can
        // move it
        StringBuilder expression = new StringBuilder("//folder[@type='root']/folder[starts-with(@ID,'")
                                   .append(Constants.FRAGMENT_ID_USER_PREFIX)
                                   .append(uid)
                                   .append("')]");
        XPathFactory fac = XPathFactory.newInstance();
        XPath xpath = fac.newXPath();
        NodeList nodes = (NodeList) xpath.evaluate(expression.toString(), userLayout,  XPathConstants.NODESET);

        // move the node as requested and save the layout
        for (int i = 0; i < nodes.getLength(); i++) {
            String sourceId = nodes.item(i).getAttributes().getNamedItem("ID").getTextContent();
            ulm.moveNode(sourceId, ulm.getParentId(destinationId), destinationId);
        }

        saveLayout(per, ulm, upm, null);
        
    }
    
	/**
	 * Move a portlet to another location on the tab.
	 * 
	 * @param ulm
	 * @param request
	 * @param response
	 * @throws IOException
	 * @throws PortalException
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=movePortlet")
	public ModelAndView movePortlet(HttpServletRequest request, HttpServletResponse response)
			throws IOException, PortalException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		// portlet to be moved
		String sourceId = request.getParameter("sourceID");

		// Either "insertBefore" or "appendAfter".
		String method = request.getParameter("method");

		// Target element to move the source element in front of.  This parameter
		// isn't actually relevant if we're appending the source element.
		String destinationId = request.getParameter("elementID");

		
		if (isTab(ulm, destinationId)) {
			// if the target is a tab type node, move the portlet to 
			// the end of the first column
		    @SuppressWarnings("unchecked")
			Enumeration columns = ulm.getChildIds(destinationId);
			if (columns.hasMoreElements()) {
				ulm.moveNode(sourceId, (String) columns.nextElement(), null);
			} else {

				IUserLayoutFolderDescription newColumn = new UserLayoutFolderDescription();
				newColumn.setName("Column");
				newColumn.setId("tbd");
				newColumn
						.setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
				newColumn.setHidden(false);
				newColumn.setUnremovable(false);
				newColumn.setImmutable(false);

				// add the column to our layout
				IUserLayoutNodeDescription col = ulm.addNode(newColumn,
						destinationId, null);

				// move the channel
				ulm.moveNode(sourceId, col.getId(), null);
			}

		} else if (ulm.getRootFolderId().equals(
			// if the target is a column type node, we need to just move the portlet
			// to the end of the column
			ulm.getParentId(ulm.getParentId(destinationId)))) {
			ulm.moveNode(sourceId, destinationId, null);

		} else {
			// If we're moving this element before another one, we need
			// to know what the target is. If there's no target, just
			// assume we're moving it to the very end of the column.
			String siblingId = null;
			if (method.equals("insertBefore"))
				siblingId = destinationId;

			// move the node as requested and save the layout
			ulm.moveNode(sourceId, ulm.getParentId(destinationId), siblingId);
		}

		try {
			// save the user's layout
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

        return new ModelAndView("jsonView", Collections.EMPTY_MAP);

	}
	
	/**
	 * Change the number of columns on a specified tab.  In the event that the user is
	 * decresasing the number of columns, extra columns will be stripped from the 
	 * right-hand side.  Any channels in these columns will be moved to the bottom of
	 * the last preserved column.
	 * 
	 * @param per
	 * @param upm
	 * @param ulm
	 * @param request
	 * @param response
	 * @throws IOException
	 * @throws PortalException
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=changeColumns")
	public ModelAndView changeColumns(HttpServletRequest request,
			HttpServletResponse response) throws IOException, PortalException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		String[] newcolumns = request.getParameterValues("columns[]");
		int columnNumber = newcolumns.length;
		String tabId = request.getParameter("tabId");
        if (tabId ==  null) tabId = (String)request.getAttribute("tabId");
        @SuppressWarnings("unchecked")
		Enumeration columns = ulm.getChildIds(tabId);
		List<String> columnList = new ArrayList<String>();
		while (columns.hasMoreElements()) {
			columnList.add((String) columns.nextElement());
		}
		List<String> newColumns = new ArrayList<String>();

		if (columnNumber > columnList.size()) {
			for (int i = columnList.size(); i < columnNumber; i++) {

				// create new column element
				IUserLayoutFolderDescription newColumn = new UserLayoutFolderDescription();
				newColumn.setName("Column");
				newColumn.setId("tbd");
				newColumn
						.setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
				newColumn.setHidden(false);
				newColumn.setUnremovable(false);
				newColumn.setImmutable(false);

				// add the column to our layout
				IUserLayoutNodeDescription node = ulm.addNode(newColumn, tabId,
						null);
				newColumns.add(node.getId());

			}
		} else if (columnNumber < columnList.size()) {
			String lastColumn = columnList.get(columnNumber - 1);
			for (int i = columnNumber; i < columnList.size(); i++) {
				String columnId = columnList.get(i);

				// move all channels in the current column to the last valid column
				@SuppressWarnings("unchecked")
				Enumeration channels = ulm.getChildIds(columnId);
				while (channels.hasMoreElements()) {
					ulm.addNode(ulm.getNode((String) channels.nextElement()),
							lastColumn, null);
				}

				// delete the column from the user's layout
				ulm.deleteNode(columnId);

			}
		}

		int count = 0;
		columns = ulm.getChildIds(tabId);
		StructureStylesheetUserPreferences ssup = upm.getUserPreferences()
		.getStructureStylesheetUserPreferences();
		while (columns.hasMoreElements()) {
			String columnId = (String) columns.nextElement();
			ssup.setFolderAttributeValue(columnId, "width", newcolumns[count] + "%");
			Element folder = ulm.getUserLayoutDOM().getElementById(columnId);
			try {
				// This sets the column attribute in memory but doesn't persist it.  Comment says saves changes "prior to persisting"
				UserPrefsHandler.setUserPreference(folder, "width", per);
			} catch (Exception e) {
				log.error("Error saving new column widths", e);
			}
			count++;
		}


		
		try {
		    saveLayout(per, ulm, upm, ssup);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

		Map<String, Object> model = new HashMap<String, Object>();
		model.put("newColumnIds", newColumns);

        return new ModelAndView("jsonView", model);

	}

	/**
	 * Move a tab left or right.
	 * 
	 * @param per
	 * @param upm
	 * @param ulm
	 * @param request
	 * @param response
	 * @throws PortalException
	 * @throws IOException
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=moveTab")
	public ModelAndView moveTab(HttpServletRequest request,
			HttpServletResponse response) throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		// gather the parameters we need to move a channel
		String destinationId = request.getParameter("elementID");
		String sourceId = request.getParameter("sourceID");
		String method = request.getParameter("method");

		// If we're moving this element before another one, we need
		// to know what the target is. If there's no target, just
		// assume we're moving it to the very end of the list.
		String siblingId = null;
		if (method.equals("insertBefore"))
			siblingId = destinationId;

		// move the node as requested and save the layout
		ulm.moveNode(sourceId, ulm.getParentId(destinationId), siblingId);

		try {
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Failed to move tab in user layout", e);
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			return null;
		}

		return new ModelAndView("jsonView", Collections.EMPTY_MAP);

	}

	/**
	 * Add a new channel.
	 * 
	 * @param per
	 * @param upm
	 * @param ulm
	 * @param request
	 * @param response
	 * @throws IOException
	 * @throws PortalException
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=addPortlet")
	public ModelAndView addPortlet(HttpServletRequest request, HttpServletResponse response) throws IOException, PortalException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);

        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		// gather the parameters we need to move a channel
		String destinationId = request.getParameter("elementID");
		int sourceId = Integer.parseInt(request.getParameter("channelID"));
		String method = request.getParameter("position");

		IChannelDefinition definition = channelRegistryStore.getChannelDefinition(sourceId);
		
        IUserLayoutChannelDescription channel = new UserLayoutChannelDescription(definition);

		IUserLayoutNodeDescription node = null;
		if (isTab(ulm, destinationId)) {
            @SuppressWarnings("unchecked")
			Enumeration columns = ulm.getChildIds(destinationId);
			if (columns.hasMoreElements()) {
				while (columns.hasMoreElements()) {
					// attempt to add this channel to the column
					node = ulm.addNode(channel, (String) columns.nextElement(),
							null);
					// if it couldn't be added to this column, go on and try the next
					// one.  otherwise, we're set.
					if (node != null)
						break;
				}
			} else {

				IUserLayoutFolderDescription newColumn = new UserLayoutFolderDescription();
				newColumn.setName("Column");
				newColumn.setId("tbd");
				newColumn
						.setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
				newColumn.setHidden(false);
				newColumn.setUnremovable(false);
				newColumn.setImmutable(false);

				// add the column to our layout
				IUserLayoutNodeDescription col = ulm.addNode(newColumn,
						destinationId, null);

				// add the channel
				node = ulm.addNode(channel, col.getId(), null);
			}

		} else if (isColumn(ulm, destinationId)) {
			// move the channel into the column
			node = ulm.addNode(channel, destinationId, null);
		} else {
			// If we're moving this element before another one, we need
			// to know what the target is. If there's no target, just
			// assume we're moving it to the very end of the column.
			String siblingId = null;
			if (method.equals("insertBefore"))
				siblingId = destinationId;

			// move the node as requested and save the layout
			node = ulm.addNode(channel, ulm.getParentId(destinationId),
					siblingId);
		}

		String nodeId = node.getId();

		try {
			// save the user's layout
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

		Map<String, String> model = new HashMap<String, String>();
		model.put("response", "Added new channel");
		model.put("newNodeId", nodeId);
		return new ModelAndView("jsonView", model);

	}

	/**
	 * Update the user's preferred skin.
	 * 
	 * @param per
	 * @param upm
	 * @param ulm
	 * @param request
	 * @param response
	 * @throws IOException
	 * @throws PortalException
	 */
    @RequestMapping(method = RequestMethod.POST, params="action=chooseSkin")
	public ModelAndView chooseSkin(HttpServletRequest request,
			HttpServletResponse response) throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);
        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();

		String skinName = request.getParameter("skinName");
        ThemeStylesheetUserPreferences themePrefs = upm.getUserPreferences().getThemeStylesheetUserPreferences();
        themePrefs.putParameterValue("skin",skinName);
		try {
		    userLayoutStore.setThemeStylesheetUserPreferences(per, upm
					.getUserPreferences().getProfile().getProfileId(), themePrefs);
		} catch (Exception e) {
			log.error("Error storing user skin preferences", e);
			response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			return null;
		}

		return new ModelAndView("jsonView", Collections.EMPTY_MAP);
	}


	/**
	 * Add a new tab to the layout.  The new tab will be appended to the end of the
	 * list and named with the BLANK_TAB_NAME variable.
	 * 
	 * @param request
	 * @throws IOException 
	 */
    @RequestMapping(method = RequestMethod.POST, params="action=addTab")
	public ModelAndView addTab(HttpServletRequest request, HttpServletResponse response) throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);
        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		// construct a brand new tab
		String id = "tbd";
        String tabName = request.getParameter("tabName");
        if (StringUtils.isBlank(tabName)) tabName = DEFAULT_TAB_NAME;
		IUserLayoutFolderDescription newTab = new UserLayoutFolderDescription();
		newTab.setName(tabName);
		newTab.setId(id);
		newTab.setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
		newTab.setHidden(false);
		newTab.setUnremovable(false);
		newTab.setImmutable(false);

		// add the tab to the layout
		ulm.addNode(newTab, ulm.getRootFolderId(), null);
		try {
			// save the user's layout
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

		// get the id of the newly added tab
		String nodeId = newTab.getId();

		try {
			// save the user's layout
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

		String[] newcolumns = request.getParameterValues("columns[]");
		if (newcolumns.length > 0) {
		    updateColumns(nodeId, newcolumns, per, upm, ulm);
		} else {
	        // pre-populate this new tab with one column
	        IUserLayoutFolderDescription newColumn = new UserLayoutFolderDescription();
	        newColumn.setName("Column");
	        newColumn.setId("tbd");
	        newColumn.setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
	        newColumn.setHidden(false);
	        newColumn.setUnremovable(false);
	        newColumn.setImmutable(false);
	        ulm.addNode(newColumn, nodeId, null);
		    
		}

		return new ModelAndView("jsonView", Collections.singletonMap("tabId", nodeId));
	}

	/**
	 * Rename a specified tab.
	 * 
	 * @param request
	 * @throws IOException 
	 */
    @RequestMapping(method = RequestMethod.POST, params = "action=renameTab")
	public ModelAndView renameTab(HttpServletRequest request, HttpServletResponse response) throws IOException {

        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);
        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

		// element ID of the tab to be renamed
		String tabId = request.getParameter("tabId");
        IUserLayoutFolderDescription tab = (IUserLayoutFolderDescription) ulm
            .getNode(tabId);

		// desired new name
		String tabName = request.getParameter("tabName");

		if (!ulm.canUpdateNode(tab)) {
		    log.warn("Attempting to rename an immutable tab");
		    response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		    return null;
		}
		
		/*
		 * Update the tab and save the layout
		 */
	    tab.setName(StringUtils.isBlank(tabName) ? DEFAULT_TAB_NAME : tabName);
		ulm.updateNode(tab);
		
		try {
			// save the user's layout
            saveLayout(per, ulm, upm, null);
		} catch (Exception e) {
			log.warn("Error saving layout", e);
		}

		// update the tab name in the in-memory structure stylesheet
		StructureStylesheetUserPreferences ssup = upm.getUserPreferences()
			.getStructureStylesheetUserPreferences();
		ssup.setFolderAttributeValue(tabId, "name", tabName);

        Map<String, String> model = Collections.singletonMap("message", "saved new tab name");
        return new ModelAndView("jsonView", model);

	}

    @RequestMapping(method = RequestMethod.POST, params = "action=updatePermissions")
    public ModelAndView updatePermissions(HttpServletRequest request, HttpServletResponse response) throws IOException {
        
        IUserInstance ui = userInstanceManager.getUserInstance(request);
        IPerson per = getPerson(ui, response);
        UserPreferencesManager upm = (UserPreferencesManager) ui.getPreferencesManager();
        IUserLayoutManager ulm = upm.getUserLayoutManager();

        String elementId = request.getParameter("elementID");
        IUserLayoutNodeDescription node = ulm.getNode(elementId);
        
        if (node == null){
            log.warn("Failed to locate node for permissions update");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return null;
        }
        
        String deletable = request.getParameter("deletable");
        if (!StringUtils.isBlank(deletable)) {
            node.setDeleteAllowed(Boolean.valueOf(deletable));
        }

        String movable = request.getParameter("movable");
        if (!StringUtils.isBlank(movable)) {
            node.setMoveAllowed(Boolean.valueOf(movable));
        }

        String editable = request.getParameter("editable");
        if (!StringUtils.isBlank(editable)) {
            node.setEditAllowed(Boolean.valueOf(editable));
        }
        
        String canAddChildren = request.getParameter("addChildAllowed");
        if (!StringUtils.isBlank(canAddChildren)) {
            node.setAddChildAllowed(Boolean.valueOf(canAddChildren));
        }
        
        ulm.updateNode(node);
        
        try {
            // save the user's layout
            saveLayout(per, ulm, upm, null);
        } catch (Exception e) {
            log.warn("Error saving layout", e);
        }

        return new ModelAndView("jsonView", Collections.EMPTY_MAP);

    }
    
    protected void removeSubscription(IPerson per, String elementId, IUserLayoutManager ulm) {
        
        // get the fragment owner's ID from the element string
        String userIdString = StringUtils.substringBetween(elementId, Constants.FRAGMENT_ID_USER_PREFIX, Constants.FRAGMENT_ID_LAYOUT_PREFIX);
        int userId = NumberUtils.toInt(userIdString,0);
        
        // construct a new person object reqpresenting the fragment owner
        RestrictedPerson fragmentOwner = PersonFactory.createRestrictedPerson();
        fragmentOwner.setID(userId);
        fragmentOwner.setUserName(userStore.getPortalUserName(userId));
        
        // attempt to find a subscription for this fragment
        IUserFragmentSubscription subscription = userFragmentInfoDao.getUserFragmentInfo(per, fragmentOwner);
        
        // if a subscription was found, remove it's registration
        if (subscription != null) {
            userFragmentInfoDao.deleteUserFragmentInfo(subscription);
            ulm.loadUserLayout(true);
        } 
        
        // otherwise, delete the node
        else {
            ulm.deleteNode(elementId);
        }
    
    }

    protected List<String> updateColumns(String tabId, String[] newcolumns, IPerson per, UserPreferencesManager upm, IUserLayoutManager ulm) throws IOException, PortalException {

        int columnNumber = newcolumns.length;
        @SuppressWarnings("unchecked")
        Enumeration columns = ulm.getChildIds(tabId);
        List<String> columnList = new ArrayList<String>();
        while (columns.hasMoreElements()) {
            columnList.add((String) columns.nextElement());
        }
        List<String> newColumns = new ArrayList<String>();

        if (columnNumber > columnList.size()) {
            for (int i = columnList.size(); i < columnNumber; i++) {

                // create new column element
                IUserLayoutFolderDescription newColumn = new UserLayoutFolderDescription();
                newColumn.setName("Column");
                newColumn.setId("tbd");
                newColumn
                        .setFolderType(IUserLayoutFolderDescription.REGULAR_TYPE);
                newColumn.setHidden(false);
                newColumn.setUnremovable(false);
                newColumn.setImmutable(false);

                // add the column to our layout
                IUserLayoutNodeDescription node = ulm.addNode(newColumn, tabId,
                        null);
                newColumns.add(node.getId());

            }
        } else if (columnNumber < columnList.size()) {
            String lastColumn = columnList.get(columnNumber - 1);
            for (int i = columnNumber; i < columnList.size(); i++) {
                String columnId = columnList.get(i);

                // move all channels in the current column to the last valid column
                @SuppressWarnings("unchecked")
                Enumeration channels = ulm.getChildIds(columnId);
                while (channels.hasMoreElements()) {
                    ulm.addNode(ulm.getNode((String) channels.nextElement()),
                            lastColumn, null);
                }

                // delete the column from the user's layout
                ulm.deleteNode(columnId);

            }
        }

        int count = 0;
        columns = ulm.getChildIds(tabId);
        StructureStylesheetUserPreferences ssup = upm.getUserPreferences()
            .getStructureStylesheetUserPreferences();
        
        while (columns.hasMoreElements()) {
            String columnId = (String) columns.nextElement();
            ssup.setFolderAttributeValue(columnId, "width", newcolumns[count] + "%");
            Element folder = ulm.getUserLayoutDOM().getElementById(columnId);
            try {
                // This sets the column attribute in memory but doesn't persist it.  Comment says saves changes "prior to persisting"
                UserPrefsHandler.setUserPreference(folder, "width", per);
            } catch (Exception e) {
                log.error("Error saving new column widths", e);
            }
            count++;
        }

        try {
            saveLayout(per, ulm, upm, ssup);
        } catch (Exception e) {
            log.warn("Error saving layout", e);
        }

        return newColumns;

    }
	/**
	 * Save the user's layout while preserving the current in-storage default
	 * tab.
	 * 
	 * @param ulm
	 * @param upm
	 * @param per
	 * @throws Exception
	 */
	protected void saveLayout(IPerson person, IUserLayoutManager ulm, UserPreferencesManager upm, StructureStylesheetUserPreferences ssup) throws Exception {

	    // save the user's layout
		ulm.saveUserLayout();

		if (ssup != null) {
            int profileId = upm.getUserPreferences().getProfile()
                    .getProfileId();

            // This is a brute force save of the new attributes. It requires
            // access to the layout store. -SAB
            userLayoutStore.setStructureStylesheetUserPreferences(person, profileId,
                    ssup);
		    
		}
		
	}
	
	/**
	 * A folder is a tab if its parent element is the layout element
	 * 
	 * @param folder the folder in question
	 * @return <code>true</code> if the folder is a tab, otherwise <code>false</code>
	 */
	protected boolean isTab(IUserLayoutManager ulm, String folderId)
			throws PortalException {
		// we could be a bit more careful here and actually check the type
		return ulm.getRootFolderId().equals(ulm.getParentId(folderId));
	}
	
	protected IPerson getPerson(IUserInstance ui, HttpServletResponse response) throws IOException {
        IPerson per = ui.getPerson();
        if (per.isGuest()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        
        return per;

	}

	/**
	 * A folder is a column if its parent is a tab element
	 * 
	 * @param folder the folder in question
	 * @return <code>true</code> if the folder is a column, otherwise <code>false</code>
	 */
	protected boolean isColumn(IUserLayoutManager ulm, String folderId)
			throws PortalException {
		return isTab(ulm, ulm.getParentId(folderId));
	}
}
