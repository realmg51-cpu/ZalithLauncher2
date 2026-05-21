/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.account

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.movtery.zalithlauncher.game.account.wardrobe.CapeFileDownloader
import com.movtery.zalithlauncher.game.account.wardrobe.SkinFileDownloader
import com.movtery.zalithlauncher.game.account.wardrobe.SkinModelType
import com.movtery.zalithlauncher.game.account.wardrobe.getLocalUUIDWithSkinModel
import com.movtery.zalithlauncher.path.PathManager
import com.movtery.zalithlauncher.utils.logging.Logger.lError
import com.movtery.zalithlauncher.utils.logging.Logger.lInfo
import com.movtery.zalithlauncher.utils.logging.Logger.lWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import java.io.File
import java.util.UUID

@Entity(tableName = "accounts")
data class Account(
    /**
     * 唯一 UUID，标识该账号
     */
    @PrimaryKey
    val uniqueUUID: String = UUID.randomUUID().toString().lowercase(),
    var accessToken: String = "0",
    var expiresAt: Long = 0L,
    var clientToken: String = "0",
    var username: String = "Steve",
    var profileId: String = getLocalUUIDWithSkinModel(username, SkinModelType.NONE),
    var refreshToken: String = "0",
    var xUid: String? = null,
    var otherBaseUrl: String? = null,
    var otherAccount: String? = null,
    var otherPassword: String? = null,
    var accountType: String? = null,
    var skinModelType: SkinModelType = SkinModelType.NONE
) {
    companion object {
        private const val MOJANG_SESSION_URL = "https://sessionserver.mojang.com"
        private const val SKIN_FILE_EXTENSION = ".png"
        private const val TEMP_FILE_SUFFIX = "_tmp"
    }

    val hasSkinFile: Boolean
        get() = getSkinFile().exists()

    val hasCapeFile: Boolean
        get() = getCapeFile().exists()

    fun getSkinFile(): File = File(PathManager.DIR_ACCOUNT_SKIN, "$uniqueUUID$SKIN_FILE_EXTENSION")

    fun getCapeFile(): File = File(PathManager.DIR_ACCOUNT_CAPE, "$uniqueUUID$SKIN_FILE_EXTENSION")

    private fun getTempSkinFile(): File = File(PathManager.DIR_ACCOUNT_SKIN, "$uniqueUUID$TEMP_FILE_SUFFIX$SKIN_FILE_EXTENSION")

    private fun getTempCapeFile(): File = File(PathManager.DIR_ACCOUNT_CAPE, "$uniqueUUID$TEMP_FILE_SUFFIX$SKIN_FILE_EXTENSION")

    /**
     * 确保账号目录存在
     */
    private fun ensureDirectories() {
        getSkinFile().parentFile?.mkdirs()
        getCapeFile().parentFile?.mkdirs()
    }

    /**
     * 获取当前账号类型的 Base URL
     */
    private fun getBaseUrl(): String? {
        return when {
            isMicrosoftAccount() -> MOJANG_SESSION_URL
            isAuthServerAccount() -> otherBaseUrl?.removeSuffix("/")?.plus("/sessionserver/")
            else -> null
        }
    }

    /**
     * 下载并更新账号的皮肤和披风文件
     * @return Boolean 是否至少有一个资源下载成功
     */
    suspend fun downloadYggdrasil(): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = getBaseUrl()
        if (baseUrl == null) {
            lWarn("Cannot download Yggdrasil resources: No valid base URL for account $username")
            return@withContext false
        }

        ensureDirectories()

        val skinDeferred = async { updateSkin(baseUrl) }
        val capeDeferred = async { updateCape(baseUrl) }
        
        val results = listOf(skinDeferred, capeDeferred).awaitAll()
        val anySuccess = results.any { it }
        
        // Chỉ refresh wardrobe một lần nếu có bất kỳ thay đổi nào
        if (anySuccess) {
            AccountsManager.refreshWardrobe()
            lInfo("Yggdrasil resources updated successfully for account $username")
        }
        
        return@withContext anySuccess
    }

    /**
     * 更新皮肤文件
     * @return Boolean 是否更新成功
     */
    private suspend fun updateSkin(baseUrl: String): Boolean {
        val targetFile = getSkinFile()
        val tempFile = getTempSkinFile()
        
        return runCatching {
            // Xóa file tạm cũ nếu tồn tại
            FileUtils.deleteQuietly(tempFile)
            
            // Tải về file tạm
            var downloadSuccess = false
            SkinFileDownloader().download(baseUrl, tempFile, profileId) { modelType ->
                this.skinModelType = modelType
                downloadSuccess = true
            }
            
            if (!downloadSuccess) {
                lWarn("No skin found for account $username")
                return@runCatching false
            }
            
            // Thay thế file cũ bằng file mới
            if (targetFile.exists()) {
                FileUtils.deleteQuietly(targetFile)
            }
            FileUtils.moveFile(tempFile, targetFile)
            
            lInfo("Skin updated successfully for account $username")
            true
        }.getOrDefault(false)
    }

    /**
     * 更新披风文件
     * @return Boolean 是否更新成功
     */
    private suspend fun updateCape(baseUrl: String): Boolean {
        val targetFile = getCapeFile()
        val tempFile = getTempCapeFile()
        
        return runCatching {
            // Xóa file tạm cũ nếu tồn tại
            FileUtils.deleteQuietly(tempFile)
            
            // Tải về file tạm
            val downloadSuccess = CapeFileDownloader().download(baseUrl, tempFile, profileId)
            
            if (!downloadSuccess) {
                lWarn("No cape found for account $username")
                return@runCatching false
            }
            
            // Thay thế file cũ bằng file mới
            if (targetFile.exists()) {
                FileUtils.deleteQuietly(targetFile)
            }
            FileUtils.moveFile(tempFile, targetFile)
            
            lInfo("Cape updated successfully for account $username")
            true
        }.getOrDefault(false)
    }

    /**
     * Xóa tất cả file skin và cape của tài khoản
     */
    fun deleteWardrobeFiles() {
        listOf(getSkinFile(), getCapeFile(), getTempSkinFile(), getTempCapeFile()).forEach { file ->
            if (file.exists()) {
                FileUtils.deleteQuietly(file)
            }
        }
        lInfo("Wardrobe files deleted for account $username")
    }

    /**
     * Kiểm tra xem tài khoản có hợp lệ để sử dụng không
     */
    fun isValid(): Boolean {
        return accessToken != "0" && 
               profileId.isNotBlank() && 
               username.isNotBlank() &&
               (expiresAt == 0L || expiresAt > System.currentTimeMillis())
    }
}
