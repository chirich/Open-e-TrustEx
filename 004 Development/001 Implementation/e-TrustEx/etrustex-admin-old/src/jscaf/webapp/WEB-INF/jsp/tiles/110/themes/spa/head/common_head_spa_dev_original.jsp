<%@ page language="java" contentType="text/html;charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://eu.europa.ec.digit.jscaf/tags" prefix="j" %>

<head>
    <meta http-equiv="Content-Type" content="text/html; charset=utf-8"/>
    <%--
    <!--[if IE]>
    <script src="<c:url value="/scripts/dev/firebug-lite/build/firebug-lite.js"/>" type="text/javascript"></script>
    <![endif]-->
    --%>
    <meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1">
    <!--[if IE]>
    <meta http-equiv="imagetoolbar" content="no">
    <![endif]-->
    <meta name="description" content="">
    <meta name="viewport" content="width=device-width, initial-scale=1, minimum-scale=1">
    <title>MyApp - 1.0.0</title>
    <link rel="SHORTCUT ICON" href="<c:url value="/styles/images/app.ico"/>"/>
                                                                                  
    <%-- jSCAF MAIN CSS --%>
    <link rel="stylesheet" href="<c:url value="/styles/reset.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/fonts.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/jquery.ui.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/bootstrap/bootstrap.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/font_default.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/font_min.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/font_max.css?v=20150403185306232"/>" type="text/css" media="screen"/>
    <link rel="stylesheet" href="<c:url value="/styles/icon-fonts.css?v=20150403185306232"/>" type="text/css" media="screen"/>

    <link rel="stylesheet" href="<c:url value="/styles/common16.css?v=20150403185306232"/>" type="text/css" media="screen"/>

    <%--SPA USAGE--%>
    <link rel="stylesheet" href="<c:url value="/themes/spa/spa.css?v=20150403185306232"/>" type="text/css" media="screen"/>


    <link rel="stylesheet" href="<c:url value="/styles/common.app.css?v=20150403185306232"/>" type="text/css" media="screen"/>

    <%-- jSCAF MAIN PRINT CSS --%>
    <link rel="stylesheet" href="<c:url value="/styles/reset.css?v=20150403185306232"/>" type="text/css" media="print"/>
    <link rel="stylesheet" href="<c:url value="/styles/jquery.ui.css?v=20150403185306232"/>" type="text/css" media="print"/>
    <link rel="stylesheet" href="<c:url value="/styles/font_default.css?v=20150403185306232"/>" type="text/css" media="print"/>

    <link rel="stylesheet" href="<c:url value="/styles/print.css?v=20150403185306232"/>" type="text/css" media="print"/>

    
    <!--jSCAF externals + modules definitions-->
    <script src="<c:url value="/scripts/jquery.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/bootstrap.light.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/core.js?v=20150403185306232"/>"></script>

    <!--jSCAF CORE + modules definitions-->
    <script src="<c:url value="/scripts/jscaf/modules/display.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/ajax.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/validation.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/ui-dialog.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/components.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/browser.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/core.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/utils.js?v=20150403185306232"/>"></script>    
    <script src="<c:url value="/scripts/jscaf/modules/resources.js?v=20150403185306232"/>"></script>      


    <%--SPA USAGE--%>
    <script src="<c:url value="/scripts/jscaf/modules/spa.js?v=20150403185306232"/>"></script>
    <script src="<c:url value="/scripts/jscaf/modules/spa-init.js?v=20150403185306232"/>"></script>


    <!--IMPORTANT ALWAYS PUT THE MAIN in last position-->
    <script src="<c:url value="/scripts/jscaf/modules/main.js?v=20150403185306232"/>"></script>

    <!--APP CONTROLLERS-->
    <script src="<c:url value="/scripts/spa/app/app.js?v=20150403185306232"/>" type="text/javascript"></script>

</head>

                                                                   

