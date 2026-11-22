package me.lendev.flow.document;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Flow 后端业务模块接入文档中心的 Spring 接口。
 *
 * <p>调用方负责校验当前登录人对目标业务记录的权限。文档中心校验文档归属、独立文档的创建人
 * 和版本归属。调用方须使用服务端确认的业务记录标识。</p>
 */
public interface DocumentCenter {

    /**
     * 标识某个已由调用模块鉴权的业务记录，三项必须同时提供。
     */
    record BusinessRecord(String module, String type, String id) {

    }

    /**
     * 文档名称、可选部门和产品，以及以 1 至 6 的槽位号为键的可选维度值。
     */
    record Metadata(String name, Long departmentId, Long productId, Map<Integer, String> dimensions) {

    }

    /**
     * 上传文件及其声明大小。调用方负责在调用结束后关闭输入流。
     */
    record Upload(String originalFileName, String contentType, long sizeBytes, InputStream content) {

    }

    /**
     * 分页及可组合的文档筛选条件，空页码默认 1，空页大小默认 20。
     */
    record Filter(Integer pageNo, Integer pageSize, String keyword, Long departmentId, Long productId,
                  Map<Integer, String> dimensions) {

    }

    /**
     * 当前查询页及授权范围内符合条件的总数。
     */
    record Page(List<Document> records, long total) {

    }

    /**
     * 文档属性、最新可用版本和业务记录锁定的版本 ID。独立文档列表返回最新版本的 SFTP 相对路径，详情提供版本历史。
     */
    record Document(long id, String name, Long departmentId, Long productId, Map<Integer, String> dimensions,
                    Version latestVersion, List<Version> versions, Long businessVersionId,
                    Instant createdAt, Instant updatedAt,
                    @JsonInclude(JsonInclude.Include.NON_NULL) String sftpPath) {

    }

    /**
     * 已发布的不可变文件版本，下载名含业务版本号。独立文档详情返回各版本的 SFTP 相对路径。
     */
    record Version(long id, long documentId, String versionNo, String originalFileName, String downloadName,
                   String contentType, long sizeBytes, Instant createdAt,
                   @JsonInclude(JsonInclude.Include.NON_NULL) String sftpPath) {

        public Version(long id, long documentId, String versionNo, String originalFileName, String downloadName,
                       String contentType, long sizeBytes, Instant createdAt) {
            this(id, documentId, versionNo, originalFileName, downloadName, contentType, sizeBytes, createdAt, null);
        }

    }

    /**
     * 在文档中心持有文件流期间同步写出下载数据。文件流由文档中心关闭。
     */
    @FunctionalInterface
    interface Download {

        /**
         * 使用版本信息设置下载响应并消费完整文件流。
         */
        void write(Version version, InputStream content) throws IOException;
    }

    /**
     * 创建文档或向已有文档上传新版本。SFTP 传输完成后分配版本号。
     *
     * @param business   业务记录，可空。为空时在当前登录人的独立文档范围内执行
     * @param documentId 已有文档 ID，可空。为空时创建文档
     * @param metadata   新文档属性。新增版本时传空
     * @param upload     待上传文件
     * @return 已发布版本，其中包含新建或已有的文档 ID
     * @throws IllegalStateException 调用时已有数据库事务或审计操作
     */
    Version upload(BusinessRecord business, Long documentId, Metadata metadata, Upload upload);

    /**
     * 查询已授权业务记录下的文档。传空业务记录可查询当前登录人自己的独立文档。
     *
     * @param business 已鉴权的业务记录，可空
     * @param filter   可选筛选和分页条件
     * @return 当前页文档和总数
     */
    Page list(BusinessRecord business, Filter filter);

    /**
     * 查询指定文档及历史版本。查询范围由业务归属或独立文档创建人确定。
     *
     * @param business   已鉴权的业务记录，可空
     * @param documentId 文档 ID
     * @return 文档详情
     */
    Document detail(BusinessRecord business, long documentId);

    /**
     * 替换文档属性。未填写的可选属性被清空。
     *
     * @param business   已鉴权的业务记录，可空
     * @param documentId 文档 ID
     * @param metadata   修改后的完整属性
     * @return 修改后的文档详情
     */
    Document update(BusinessRecord business, long documentId, Metadata metadata);

    /**
     * 在业务保存时挂接独立文档或锁定该业务文档的一个可用版本。 调用方可在业务数据库事务内调用，业务回滚时文档绑定一起回滚。 重复保存相同绑定和版本时返回当前结果。
     *
     * @param business   已鉴权的目标业务记录
     * @param documentId 文档 ID
     * @param versionId  本次业务记录保存所引用的文件版本 ID
     * @return 更新后的文档详情
     */
    Document bind(BusinessRecord business, long documentId, long versionId);

    /**
     * 从业务记录移除文档，同时解除版本锁定。文件及其历史版本归回创建人的独立文档。
     *
     * @param business   已鉴权的业务记录
     * @param documentId 文档 ID
     */
    void detach(BusinessRecord business, long documentId);

    /**
     * 下载业务记录固定的版本，或者下载独立文档指定的版本。
     *
     * @param business   已鉴权的业务记录，可空
     * @param documentId 文档 ID
     * @param versionId  独立文档可指定历史版本，传空取最新版本。业务下载传空
     * @param download   文件写出回调
     * @throws IllegalStateException 调用时已有数据库事务或审计操作
     */
    void download(BusinessRecord business, long documentId, Long versionId, Download download);

    /**
     * 逻辑删除当前登录人的独立文档，并安排远端文件清理。
     *
     * @param documentId 独立文档 ID
     */
    void delete(long documentId);
}
