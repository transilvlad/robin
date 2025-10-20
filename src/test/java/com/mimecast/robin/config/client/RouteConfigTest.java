package com.mimecast.robin.config.client;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Foundation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.naming.ConfigurationException;

import static org.junit.jupiter.api.Assertions.*;

class RouteConfigTest {

    private static RouteConfig routeConfig1;
    private static RouteConfig routeConfig2;

    @BeforeAll
    static void before() throws ConfigurationException {
        Foundation.init("src/test/resources/cfg/");

        routeConfig1 = Config.getClient().getRoute("com");
        routeConfig2 = Config.getClient().getRoute("net");
    }

    @Test
    void getName() {
        assertEquals("com", routeConfig1.getName());

        assertEquals("net", routeConfig2.getName());
    }

    @Test
    void getMx() {
        assertEquals("example.com", routeConfig1.getMx().get(0));

        assertEquals("example.net", routeConfig2.getMx().get(0));
    }

    @Test
    void getSmtpPort() {
        assertEquals(25, routeConfig1.getSmtpPort());

        assertEquals(465, routeConfig2.getSmtpPort());
    }

    @Test
    void getAuth() {
        assertFalse(routeConfig1.isAuth());

        assertTrue(routeConfig2.isAuth());
    }

    @Test
    void getUser() {
        assertNull(routeConfig1.getUser());

        assertEquals("tony@example.com", routeConfig2.getUser());
    }

    @Test
    void getPass() {
        assertNull(routeConfig1.getPass());

        assertEquals("giveHerTheRing", routeConfig2.getPass());
    }
}
