<%@ page language="java" contentType="text/html; charset=UTF-8"
    pageEncoding="UTF-8"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
<head>
<meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
<title>Insert title here</title>
<script type="text/javascript">
	var AdminValue;
	var mainValue;
	var Guest1Value;
	var Guest2Value;
	function mainSend(){
		mainValue = document.getElementById("textMain").value;
		AdminValue = mainValue+1024;
		document.getElementById("messageAdmin").value=AdminValue;
		alert("主机原信息为"+mainValue+",发送给管理员的信息为加密后的"+AdminValue);
	}
	function adminSend(){
		if(document.getElementById("textAdmin").value=="1"){
			Guest1Value = AdminValue+document.getElementById("textAdmin").value;
			document.getElementById("messageGuest1").value=Guest1Value;
			alert("管理员原信息为"+AdminValue+",发送给客户机1的信息为加密后的"+Guest1Value);
		}else{
 			Guest2Value = AdminValue+document.getElementById("textAdmin").value;
 			document.getElementById("messageGuest2").value=Guest2Value;
			alert("管理员原信息为"+AdminValue+",发送给客户机2的信息为加密后的"+Guest2Value);
		}
		
	
	}
	function check1(){
		if(document.getElementById("textGuest1").value-10241==mainValue*100000){
			alert("校验成功！");
		}else{
			alert("校验失败！");
		}
		
	}
	function check2(){
		if(document.getElementById("textGuest2").value-10242==mainValue*100000){
			alert("校验成功！");
		}else{
			alert("校验失败！");
		}
	}

</script>
</head>
<body>
	<input type="text" id="textMain"><input type="button" value="主机发送" onclick="mainSend()"/><input type="button" value="主机校验"/><input type="text" id="textMain"></br>
<input type="text" id="textAdmin"><input type="button" value="管理员转发" onclick="adminSend()"/><input type="text" id="messageAdmin"></br>
<input type="text" id="textGuest1"><input type="button" value="客户机1验证" onclick="check1()"/><input type="text" id="messageGuest1"></br>
<input type="text" id="textGuest2"><input type="button" value="客户机2验证" onclick="check2()"/><input type="text" id="messageGuest2"></br>

</body>
</html>