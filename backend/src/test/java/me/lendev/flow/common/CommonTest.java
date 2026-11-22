package me.lendev.flow.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.hutool.core.lang.tree.Tree;

import java.util.List;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.common.exception.BizException;
import me.lendev.flow.common.request.PageRequest;
import me.lendev.flow.common.response.BizResponseCode;
import me.lendev.flow.common.response.Page;
import me.lendev.flow.common.response.R;
import me.lendev.flow.pms.domain.entity.Permission;
import me.lendev.flow.pms.util.PermissionUtil;
import org.junit.jupiter.api.Test;

/**
 * 公共类的单元测试
 */
class CommonTest {

    @Test
    void responseWrapper() {
        R<Void> ok = R.ok();
        assertEquals(0, ok.getCode());
        assertEquals("OK", ok.getMessage());
        assertNull(ok.getData());

        R<String> withData = R.ok("payload");
        assertEquals("payload", withData.getData());

        R<Object> built = R.build(new BizException(BizResponseCode.ERR_10001));
        assertEquals(10001, built.getCode());
        assertEquals("用户已存在", built.getMessage());
        assertNull(built.getData());

        R<String> chained = new R<String>().setCode(7).setMessage("msg").setData("d");
        assertEquals(7, chained.getCode());
        assertEquals("msg", chained.getMessage());
        assertEquals("d", chained.getData());
    }

    @Test
    void pageConversion() {
        com.mybatisflex.core.paginate.Page<String> flexPage =
                new com.mybatisflex.core.paginate.Page<>(1, 10);
        flexPage.setRecords(List.of("a", "b"));
        flexPage.setTotalRow(2);

        Page<String> page = Page.convert(flexPage);
        assertEquals(List.of("a", "b"), page.getPageData());
        assertEquals(2L, page.getTotal());
    }

    @Test
    void pageRequestConversion() {
        PageRequest request = new PageRequest();
        assertEquals(Integer.valueOf(1), request.getPageNo());
        assertEquals(Integer.valueOf(10), request.getPageSize());

        com.mybatisflex.core.paginate.Page<Object> page = request.toPage();
        assertEquals(1L, page.getPageNumber());
        assertEquals(10L, page.getPageSize());
        request.setPageSize(1000);
        assertEquals(500L, request.toPage().getPageSize());

        request.setPageNo(3);
        request.setPageSize(5);
        assertEquals(3L, request.toPage().getPageNumber());
        assertEquals(5L, request.toPage().getPageSize());
    }

    @Test
    void exceptions() {
        BizException fromCode = new BizException(BizResponseCode.ERR_10002);
        assertEquals(BizResponseCode.ERR_10002, fromCode.getCode());
        assertEquals("用户名或密码错误", fromCode.getMessage());

        BizException fromMessage = new BizException(BizResponseCode.ERR_11006, "自定义消息");
        assertEquals("自定义消息", fromMessage.getMessage());
        assertEquals(BizResponseCode.ERR_11006, fromMessage.getCode());

        BadRequestException badRequest = new BadRequestException("参数错误");
        assertEquals("参数错误", badRequest.getMessage());
        assertThrows(BadRequestException.class, () -> {
            throw badRequest;
        });
    }

    @Test
    void responseCodes() {
        assertEquals(0, BizResponseCode.OK.getCode());
        assertEquals(400, BizResponseCode.ERR_400.getCode());
        assertEquals(BizResponseCode.ERR_11004, BizResponseCode.valueOf("ERR_11004"));
        for (BizResponseCode code : BizResponseCode.values()) {
            assertNotNull(code.getMsg());
            assertTrue(code.getCode() >= 0);
        }
    }

    @Test
    void permissionTree() {
        Permission parent = new Permission();
        parent.setId(1L);
        parent.setName("父节点");
        parent.setOrder(1);
        Permission child = new Permission();
        child.setId(2L);
        child.setParentId(1L);
        child.setName("子节点");
        child.setOrder(2);

        List<Tree<Long>> tree = PermissionUtil.toTreeNode(List.of(parent, child), null);
        assertEquals(1, tree.size());
        assertEquals("父节点", tree.getFirst().getName());
        assertEquals(1, tree.getFirst().getChildren().size());
        assertEquals("子节点", tree.getFirst().getChildren().getFirst().getName());
    }


}
