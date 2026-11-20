package org.apache.ftpserver;

import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ftplet.FileSystemView2;
import org.apache.ftpserver.ftplet.FileSystemView;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.FtpServer;

import java.util.List;

public class FtpServerFactory2 extends FtpServerFactory {

    public final String host;
    public final int port;

    public FtpServerFactory2(String host, int port) {
        this.host = host;
        this.port = port;
        ListenerFactory listenerFactory = new ListenerFactory();
        listenerFactory.setServerAddress(host);
        listenerFactory.setPort(port);
        addListener("default", listenerFactory.createListener());
    }

    public void addUser(String username, Authority auth) throws FtpException {
        BaseUser u = new BaseUser();
        u.setName(username);
        u.setHomeDirectory("/");
        u.setAuthorities(List.<Authority>of(auth));
        getUserManager().save(u);
    }

    public FtpServer start() throws FtpException {
        FtpServer s = createServer();
        s.start();
        System.out.println("FTP server running at " + host + ":" + port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> s.stop()));
        return s;
    }
    
}
