package com.onsamiro.transcriber;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

public final class LegacyResultAuditor {
    public static final class Finding { public String name,reason; Finding(String n,String r){name=n;reason=r;} }
    public static List<Finding> audit(Context c,int maxFiles){List<Finding> out=new ArrayList<>();AppConfig cfg=new AppConfig(c);if(cfg.outputTree().isEmpty())return out;Uri tree=Uri.parse(cfg.outputTree());String root=DocumentsContract.getTreeDocumentId(tree);Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,root);String[] p={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE};int seen=0;try(Cursor cur=c.getContentResolver().query(children,p,null,null,null)){if(cur==null)return out;while(cur.moveToNext()&&seen++<maxFiles){String id=cur.getString(0),name=cur.getString(1),mime=cur.getString(2);if(name==null||!name.toLowerCase().endsWith(".txt"))continue;Uri doc=DocumentsContract.buildDocumentUriUsingTree(tree,id);StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(c.getContentResolver().openInputStream(doc)))){String line;while((line=r.readLine())!=null&&s.length()<20_000)s.append(line).append(' ');}String text=s.toString().replaceAll("\\s+"," ").trim();String compact=text.replaceAll("[\\s\\p{Punct}]+","");if(compact.length()<20)out.add(new Finding(name,"내용이 매우 짧음"));else if(text.matches(".*(음[ ,.]*){5,}.*"))out.add(new Finding(name,"반복 음절 의심"));} }catch(Throwable t){out.add(new Finding("검사 중단",t.getClass().getSimpleName()));}return out;}
}
