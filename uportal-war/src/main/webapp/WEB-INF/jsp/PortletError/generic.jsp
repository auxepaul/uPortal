<%--

    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.

--%>

<%@ include file="/WEB-INF/jsp/include.jsp"%>
<div class="fl-widget portlet error view-detailed" role="section">

<div class="fl-widget-titlebar titlebar portlet-titlebar" role="sectionhead">
<p><spring:message code="errorportlet.main"/></p>
<%-- 
<div class="breadcrumb">
<portlet:renderURL var="retryUrl"></portlet:renderURL>
<portlet:renderURL var="resetUrl"></portlet:renderURL>
<span class="breadcrumb-1"><a href="${ retryUrl }"><spring:message code="errorportlet.retry"/></a></span>
<span class="separator">&nbsp;</span>
<span class="breadcrumb-2"><a href="${ resetUrl }"><spring:message code="errorportlet.reset"/></a></span>
</div> <!-- end breadcrumbs -->
--%>
</div> <!-- end sectionhead -->

</div> <!--  end portlet -->